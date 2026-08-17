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
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V4-only (maintainer ruling M-2 — "best-effort v4 repair units", see "v3 → v5 migration
 * consolidation" in {@code specifications/merge-execution-plan.md}). Rebuilds the {@code
 * WORKFLOW}-scope {@code audit} records ({@code AuditEntity}/{@code AuditScope}) a v4 install is
 * missing.
 *
 * <p><b>The v4 bug:</b> legacy {@code 4038} tried to seed audit records from the relationship
 * graph for BOTH workspaces and workflows, but its workflow half matched {@code
 * Filters.eq("type","WORKFLOW")} against the relationship node {@code type} field — every node
 * type in this codebase is written lowercase ({@code RelationshipType.getLabel()} — {@code
 * "workflow"}, never {@code "WORKFLOW"}), so that filter matched nothing on any real v4 install.
 * Its workspace half used the correct casing and DID work, so a v4 install has {@code
 * scope=WORKSPACE} audit records already (once {@link _0016__WorkspaceRename} has normalised the
 * legacy {@code scope=TEAM} value it originally wrote — this unit does not touch or recreate those
 * — see the "what
 * this unit assumes already exists" note below) but zero {@code scope=WORKFLOW} ones. This is
 * exactly {@link _0013__V3SeedAudit}'s finding, re-applied here for the v4 case: that unit seeds
 * BOTH scopes from scratch (a v3 install never had an {@code audit} collection at all); this one
 * only backfills the workflow half a v4 install is specifically missing.
 *
 * <p><b>Field mapping</b> — identical to {@link _0013__V3SeedAudit}'s workflow-record mapping
 * (see that unit's javadoc for the full field-by-field justification against {@code
 * AuditInterceptor}/{@code InsightsService}): {@code scope=WORKFLOW}; {@code selfRef} <- the
 * workflow's own {@code _id}; {@code selfName} <- its {@code name}; {@code parent} <- the owning
 * workspace's OWN AUDIT RECORD id (never the workspace's domain {@code _id}), resolved by walking
 * {@code rel_edges} for the {@code hasWorkflow} edge pointing at {@code workflow:<id>} and reading
 * its {@code from} workspace's EXISTING {@code scope=WORKSPACE} audit record; {@code data} <- {@code
 * {name: <workflow name>}}.
 *
 * <p><b>What this unit assumes already exists, and never creates itself:</b> {@code
 * scope=WORKSPACE} workspace audit records — legacy {@code 4038}'s workspace half worked correctly on v4, so every
 * v4 install's workspaces already have one. If a workflow's resolved workspace turns out to have
 * no audit record anyway (should not happen on a genuine v4 install, but handled defensively), its
 * workflow audit record is skipped and logged rather than written with a fabricated/null parent —
 * matching {@link _0013__V3SeedAudit}'s own "unresolved" handling. Likewise, {@code rel_nodes}/
 * {@code rel_edges} themselves are assumed already correct on v4 ({@code 4041} is the KEEP'd
 * relationship-model introduction, and {@link _0016__WorkspaceRename} — ungated, runs on every
 * install — has already normalised any {@code team:}-prefixed writes to {@code workspace:} by the
 * time this unit runs).
 *
 * <p><b>Approver groups are NOT repairable on v4</b> (per M-2, same finding as {@link
 * _0024__V4RepairTaskVersions}'s javadoc): legacy {@code 4011} stripped {@code
 * teams.approverGroups[]} during the v3→v4 migration and nothing ever wrote a v4-side {@code
 * approver_groups} collection to replace it. There is no surviving fragment anywhere to rebuild
 * it from, so no repair unit attempts it — the gap is permanent on v4 installs and is documented
 * here (and on {@code _0036}) purely so it stays discoverable.
 *
 * <p>Idempotent: matched (and skipped) by {@code (scope=WORKFLOW, selfRef)} — a second run
 * inserts nothing new, mirroring {@link _0013__V3SeedAudit}.
 */
@Change(id = "0025-v4-repair-workflow-audit", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0025__V4RepairWorkflowAudit {

  private static final Logger LOG = LoggerFactory.getLogger(_0025__V4RepairWorkflowAudit.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V4) {
      LOG.info("Not a v4 install — workflow audit records are seeded (or not needed) elsewhere.");
      return;
    }

    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> relEdges = db.getCollection(names.resolve("rel_edges"));
    MongoCollection<Document> audit = db.getCollection(names.resolve("audit"));

    long inserted = 0;
    long noHasWorkflowEdge = 0;
    long noWorkspaceAudit = 0;

    for (Document workflow : workflows.find()) {
      String workflowId = workflow.get("_id").toString();
      if (audit.find(Filters.and(Filters.eq("scope", "WORKFLOW"), Filters.eq("selfRef", workflowId))).first()
          != null) {
        continue;
      }

      String workflowNodeId = "workflow:" + workflowId;
      Document hasWorkflowEdge =
          relEdges.find(Filters.and(Filters.eq("label", "hasWorkflow"), Filters.eq("to", workflowNodeId))).first();
      if (hasWorkflowEdge == null) {
        noHasWorkflowEdge++;
        LOG.warn("No hasWorkflow edge found for workflow {} — skipping its audit record", workflowId);
        continue;
      }

      String workspaceNodeId = hasWorkflowEdge.getString("from");
      String workspaceId = workspaceNodeId.substring("workspace:".length());
      Document workspaceAudit =
          audit.find(Filters.and(Filters.eq("scope", "WORKSPACE"), Filters.eq("selfRef", workspaceId))).first();
      if (workspaceAudit == null) {
        noWorkspaceAudit++;
        LOG.warn(
            "Workflow {} resolves to workspace {} which has no WORKSPACE audit record (expected to"
                + " already exist on a v4 install) — skipping",
            workflowId,
            workspaceId);
        continue;
      }

      String name = workflow.getString("name");
      Document record =
          new Document("_id", new ObjectId())
              .append("scope", "WORKFLOW")
              .append("selfRef", workflowId)
              .append("selfName", name)
              .append("parent", workspaceAudit.get("_id").toString())
              .append("creationDate", new Date())
              .append("events", List.of())
              .append("data", new Document("name", name));
      audit.insertOne(record);
      inserted++;
    }

    LOG.info(
        "v4 workflow audit repair — {} inserted, {} skipped (no hasWorkflow edge), {} skipped (no"
            + " workspace audit record)",
        inserted,
        noHasWorkflowEdge,
        noWorkspaceAudit);
  }

  @Rollback
  public void rollback() {
    // Audit history is meant to outlive the objects it describes - not restorable, matching the
    // other forward-only v3/v4-only units in this chain.
  }
}
