package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Creates {@code audit} records ({@code AuditEntity}/{@code AuditScope}) for every
 * workspace and workflow — the "audit trail exists even for objects later deleted" guarantee
 * {@code InsightsService} depends on ({@code auditQueryService.findFirstByScopeAndSelfName}/{@code
 * findByScopeAndParent}/{@code findFirstByScopeAndSelfRef}), which a v3 install has never had since
 * the {@code audit} collection is a v5-only concept.
 *
 * <p><b>H14-c note:</b> this unit writes the DD-01 {@code scope="WORKSPACE"} value directly (the
 * enum name {@code AuditScope} deserialises), so the database is loadable by the application at
 * every point in the chain. An earlier revision wrote the pre-rename {@code "TEAM"} value and
 * relied on {@link _0016__WorkspaceRename} to rewrite it; {@code _0016} still performs that
 * rewrite for databases seeded by that revision.
 *
 * <p><b>Legacy 4038 is the nominal reference, but its workflow half never actually worked</b>
 * (verified against the live {@code AuditInterceptor}/relationship-graph code, matching the batch
 * instructions' own finding): it matched {@code Filters.eq("type","WORKFLOW")} against the
 * relationship node {@code type} field, but every node type in this codebase is written lowercase
 * ({@code RelationshipType.getLabel()} — {@code "workflow"}, never {@code "WORKFLOW"}), so that
 * filter matched nothing on any real v4 install and no workflow audit record was ever created.
 * This unit resolves the parent workspace via {@code rel_edges} instead (the graph {@link
 * _0012__V3BuildRelationshipGraph} already built, in the correct lowercase shape), never by
 * re-matching on an uppercase node type.
 *
 * <p><b>Field mapping, verified against {@code AuditEntity}/{@code AuditScope}/{@code
 * AuditInterceptor}/{@code InsightsService} (the live create- and read-paths):</b>
 *
 * <ul>
 *   <li><b>Workspace records</b> — one per {@code teams} document (all 86: real v3 teams, the
 *       seeded {@code system} workspace, and Batch C's personal workspaces alike; {@code
 *       AuditInterceptor.createTeam} fires for every {@code WorkspaceService.create} call
 *       regardless of type, so there is no type to exclude here): {@code scope=TEAM} (the
 *       enum's raw {@code name()} — Spring Data's default enum conversion, no custom converter
 *       registered for {@code AuditScope} anywhere in this codebase — NOT {@code
 *       AuditScope.getLabel()}, which would write the lowercase {@code "team"} and never match a
 *       repository query built against the typed enum); {@code selfRef} <- the workspace's own
 *       {@code _id} (matches {@code createTeam}: {@code reflectGetter(entity, "getId")});
 *       {@code selfName} <- the workspace's {@code name} (matches {@code createTeam}'s {@code
 *       reflectGetter(entity, "getName")} — and is what {@code InsightsService.get}'s {@code
 *       findFirstByScopeAndSelfName(TEAM, team)} actually looks up by, {@code team} being the
 *       slug passed on the API path); {@code parent} <- absent (workspaces have no parent audit
 *       object); {@code data} <- {@code {name: <workspace name>}} (matches {@code createTeam}'s
 *       {@code Map.of("name", entityName)}).
 *   <li><b>Workflow records</b> — one per {@code workflows} document (all 65): {@code
 *       scope=WORKFLOW}; {@code selfRef} <- the workflow's own {@code _id} — a DELIBERATE
 *       departure from {@code AuditInterceptor.createWorkflow}'s live {@code selfRef =
 *       entity.getName()}, which does not match how a workflow audit record is actually looked
 *       up ({@code InsightsService.get}: {@code findFirstByScopeAndSelfRef(WORKFLOW, <a
 *       workflowRef id, from workflow-run audit data>)}) — using the id here is what makes the
 *       seeded records actually resolvable by that read path, not a fidelity break for its own
 *       sake; {@code AuditInterceptor} itself is service-core and out of scope for this migration
 *       program to touch. {@code selfName} <- the workflow's {@code name}; {@code parent} <- the
 *       owning workspace's OWN AUDIT RECORD id (never the workspace's domain {@code _id} — {@code
 *       AuditEntity.parent}'s field comment is explicit: "Reference to the parent audit object"),
 *       resolved by walking {@code rel_edges} for the {@code hasWorkflow} edge pointing at {@code
 *       workflow:<id>} and looking its {@code from} up in the workspace-audit-id map built in the
 *       same pass — never by re-deriving the owner from {@code workflows.scope}/{@code ownerRef}
 *       (those were {@link _0012__V3BuildRelationshipGraph}'s job; this unit trusts the graph it
 *       produced, per the batch instructions); {@code data} <- {@code {name: <workflow name>}}.
 *       A workflow whose {@code hasWorkflow} edge is missing (should not happen — {@code _0029}
 *       resolved all 65) is logged and skipped rather than written with a null parent.
 * </ul>
 *
 * <p>Idempotent: matched (and skipped) by {@code (scope, selfRef)} — a second run inserts nothing
 * new, and the workspace-audit-id map used to resolve workflow parents is rebuilt from whatever
 * records exist (freshly inserted this run, or already present from a prior run) either way.
 */
@Change(id = "0013-v3-seed-audit", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0013__V3SeedAudit {

  private static final Logger LOG = LoggerFactory.getLogger(_0013__V3SeedAudit.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — audit records are seeded (or not needed) elsewhere.");
      return;
    }

    Map<String, String> auditIdByWorkspaceId = seedWorkspaceAudits(db, names);
    long[] workflowCounts = seedWorkflowAudits(db, names, auditIdByWorkspaceId);

    LOG.info(
        "v3 audit records seeded — {} workspace records, {} workflow records ({} unresolved parent)",
        auditIdByWorkspaceId.size(),
        workflowCounts[0],
        workflowCounts[1]);
  }

  // =====================================================================================
  // WORKSPACE scope — one per workspace
  // =====================================================================================

  private Map<String, String> seedWorkspaceAudits(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> teams = db.getCollection(names.resolve("teams"));
    MongoCollection<Document> audit = db.getCollection(names.resolve("audit"));
    Map<String, String> auditIdByWorkspaceId = new HashMap<>();

    for (Document workspace : teams.find()) {
      String workspaceId = workspace.get("_id").toString();
      String name = workspace.getString("name");

      Document existing =
          audit.find(
                  Filters.and(
                      Filters.in("scope", "WORKSPACE", "TEAM"), Filters.eq("selfRef", workspaceId)))
              .first();
      if (existing != null) {
        auditIdByWorkspaceId.put(workspaceId, existing.get("_id").toString());
        continue;
      }

      ObjectId auditId = new ObjectId();
      Document record =
          new Document("_id", auditId)
              .append("scope", "WORKSPACE")
              .append("selfRef", workspaceId)
              .append("selfName", name)
              .append("creationDate", new Date())
              .append("events", List.of())
              .append("data", new Document("name", name));
      audit.insertOne(record);
      auditIdByWorkspaceId.put(workspaceId, auditId.toString());
    }
    return auditIdByWorkspaceId;
  }

  // =====================================================================================
  // WORKFLOW scope — one per workflow, parent resolved via rel_edges
  // =====================================================================================

  private long[] seedWorkflowAudits(MongoDatabase db, CollectionNames names, Map<String, String> auditIdByWorkspaceId) {
    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> relEdges = db.getCollection(names.resolve("rel_edges"));
    MongoCollection<Document> audit = db.getCollection(names.resolve("audit"));

    long inserted = 0;
    long unresolved = 0;
    for (Document workflow : workflows.find()) {
      String workflowId = workflow.get("_id").toString();
      String name = workflow.getString("name");
      String workflowNodeId = "workflow:" + workflowId;

      Document hasWorkflowEdge = relEdges.find(Filters.and(Filters.eq("label", "hasWorkflow"), Filters.eq("to", workflowNodeId))).first();
      if (hasWorkflowEdge == null) {
        unresolved++;
        LOG.warn("No hasWorkflow edge found for workflow {} — skipping its audit record", workflowId);
        continue;
      }
      String workspaceNodeId = hasWorkflowEdge.getString("from");
      String workspaceId = workspaceNodeId.substring("workspace:".length());
      String parentAuditId = auditIdByWorkspaceId.get(workspaceId);
      if (parentAuditId == null) {
        unresolved++;
        LOG.warn("Workflow {} resolves to workspace {} which has no audit record — skipping", workflowId, workspaceId);
        continue;
      }

      if (audit.find(Filters.and(Filters.eq("scope", "WORKFLOW"), Filters.eq("selfRef", workflowId))).first() != null) {
        continue;
      }

      Document record =
          new Document("_id", new ObjectId())
              .append("scope", "WORKFLOW")
              .append("selfRef", workflowId)
              .append("selfName", name)
              .append("parent", parentAuditId)
              .append("creationDate", new Date())
              .append("events", List.of())
              .append("data", new Document("name", name));
      audit.insertOne(record);
      inserted++;
    }
    return new long[] {inserted, unresolved};
  }

  @Rollback
  public void rollback() {
    // Audit history is meant to outlive the objects it describes - not restorable, matching the
    // other forward-only v3-only units in this chain.
  }
}
