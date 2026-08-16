package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Runs immediately after {@code _0023__V3MigrateWorkflows} (same chain, next numeric
 * order) — it depends on that unit having ALREADY reshaped every v3 workflow (including {@code
 * scope=template} ones) into v5 {@code WorkflowEntity}/{@code WorkflowRevisionEntity} shape,
 * including the {@code scope} extra field {@code _0023} preserves specifically so this unit (and
 * later, Batch E) can find them without re-reading the by-then-already-rewritten v3 documents.
 *
 * <p>Extracts every {@code scope=template} workflow's already-migrated revision(s) into one {@code
 * workflow_templates} document per revision (folding the owning workflow's fields in), then
 * deletes the source workflow and its revision(s) — squashing legacy {@code 4016} (the extraction
 * itself), {@code 4035} (the same {@code templateRef}->{@code taskRef}/{@code taskVersion} bug
 * legacy {@code 4034} had — moot here, since the revisions this unit reads are already correctly
 * migrated by {@code _0023}) and {@code 4046} (params/config merge — already done by {@code
 * _0023} too, since it operates on already-v5-shaped {@code workflow_revisions.params}).
 *
 * <p><b>Field mapping, verified against a real v3 dump (exactly 2 {@code scope=template} workflows
 * on this install, matching the batch instructions' collision-guard section) and against {@code
 * WorkflowTemplateEntity}:</b>
 *
 * <ul>
 *   <li>{@code _id} <- the already-migrated {@code workflow_revisions} document's OWN {@code _id}
 *       (preserved verbatim by {@code _0023} from v3, matching legacy {@code 4016}'s own behaviour
 *       of inserting the revision document itself, {@code _id} untouched, into {@code
 *       workflow_templates}). Verified against the real dump: the two source workflows' v1
 *       revisions carry ids {@code 62be6a3266ff43491f09d2e8} and {@code 62be6a3e66ff43491f09d2ea} —
 *       EXACTLY the two ids {@code _0018__SeedTemplates}'s collision guard names.
 *   <li>{@code name}/{@code displayName}/{@code icon}/{@code description}/{@code labels}
 *       <- the owning workflow's ALREADY-migrated fields directly (unlike legacy {@code 4016},
 *       which had to re-derive the slug and the description/shortDescription fallback inline
 *       because {@code 4021}/{@code 4047} hadn't run yet at legacy's {@code 4016} order position —
 *       {@code _0023} already resolved both correctly by the time this unit runs, so no
 *       re-derivation is needed here).
 *   <li>{@code creationDate} <- the owning workflow's {@code creationDate} (matches legacy {@code
 *       4016}: {@code revision.put("creationDate", wfTemplate.get("creationDate"))} — the
 *       WORKFLOW's creation date, not the revision's own changelog date).
 *   <li>{@code annotations} <- {@code {"boomerang#io/generation":"3",
 *       "boomerang#io/kind":"WorkflowTemplate"}} (matches {@code 4016}, same escaping convention as
 *       every other unit in this program).
 *   <li>{@code version}/{@code tasks}/{@code changelog}/{@code params}/{@code workspaces} <- the
 *       revision's own already-v5-shaped fields, copied straight across (already carry the fixed
 *       {@code taskRef}/{@code taskVersion} from {@code _0023} — see that unit's headline fix).
 *   <li>{@code workflowRef} is dropped (not a {@code WorkflowTemplateEntity} field — matches
 *       legacy {@code 4016}'s explicit {@code revision.remove("workflowRef")}).
 * </ul>
 *
 * <p><b>Collision guard</b> (batch instructions): {@code _0018__SeedTemplates} seeds two documents
 * with {@code _id} {@code 62be6a32…e8}/{@code 62be6a3e…ea} whose SOURCE workflows are {@code
 * 62be6a32…e7}/{@code 62be6a3e…e9} — on v3, {@code _0018} always SKIPS (generation-aware, see its
 * own javadoc), so this unit is what actually produces them on a v3 install. Still made safe if
 * they somehow already exist: insertion is guarded by a {@code _id} existence check (skip-if-
 * present, not upsert), and the source workflow/revision are deleted EITHER WAY — an already-
 * present target document never blocks cleanup of the source.
 *
 * <p><b>Idempotency.</b> A second run finds zero {@code scope=template} workflows — this unit's own
 * first run deletes them once fully processed, so the query that drives the whole loop naturally
 * returns nothing on any later run. Crash recovery mid-way is also safe: a crash after inserting
 * (some) templates but before deleting the source is caught by the {@code _id} existence guard on
 * retry (insert is skipped, delete proceeds); a crash after deleting all of a workflow's revisions
 * but before deleting the workflow itself simply finds zero revisions for that workflow on retry
 * (the per-revision loop body does nothing) and proceeds straight to deleting the workflow.
 */
@Change(id = "0024-v3-extract-workflow-templates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0024__V3ExtractWorkflowTemplates {

  private static final Logger LOG = LoggerFactory.getLogger(_0024__V3ExtractWorkflowTemplates.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — template workflows already extracted (or never existed).");
      return;
    }

    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> revisions = db.getCollection(names.resolve("workflow_revisions"));
    MongoCollection<Document> templates = db.getCollection(names.resolve("workflow_templates"));

    long extracted = 0;
    long alreadyPresent = 0;
    long workflowsRemoved = 0;

    // The "scope" extra field _0023 preserves specifically for this lookup - see that unit's
    // javadoc.
    for (Document workflow : workflows.find(Filters.eq("scope", "template")).into(new ArrayList<>())) {
      ObjectId workflowId = workflow.getObjectId("_id");
      String workflowRef = workflowId.toString();

      List<Document> workflowRevisions =
          revisions.find(Filters.eq("workflowRef", workflowRef)).into(new ArrayList<>());
      for (Document revision : workflowRevisions) {
        ObjectId templateId = revision.getObjectId("_id");
        if (templates.find(Filters.eq("_id", templateId)).first() == null) {
          templates.insertOne(buildTemplate(workflow, revision, templateId));
          extracted++;
        } else {
          alreadyPresent++;
        }
        revisions.deleteOne(Filters.eq("_id", templateId));
      }

      workflows.deleteOne(Filters.eq("_id", workflowId));
      workflowsRemoved++;
    }

    LOG.info(
        "v3 template workflows extracted — {} template(s) inserted, {} already present "
            + "(collision guard), {} source workflow(s) removed",
        extracted,
        alreadyPresent,
        workflowsRemoved);
  }

  private Document buildTemplate(Document workflow, Document revision, ObjectId templateId) {
    Document template = new Document();
    template.put("_id", templateId);
    template.put("name", workflow.getString("name"));
    template.put("displayName", workflow.getString("displayName"));
    template.put("creationDate", workflow.getDate("creationDate"));
    template.put("icon", workflow.get("icon"));
    template.put("description", workflow.getString("description"));
    template.put("labels", workflow.get("labels"));
    template.put(
        "annotations",
        new Document("boomerang#io/generation", "3").append("boomerang#io/kind", "WorkflowTemplate"));
    template.put("version", revision.getInteger("version"));
    template.put("tasks", revision.get("tasks"));
    template.put("changelog", revision.get("changelog"));
    template.put("params", revision.get("params"));
    template.put("workspaces", revision.get("workspaces"));
    return template;
  }

  @Rollback
  public void rollback() {
    // The source workflow and revision(s) are deleted once extracted - not restorable, matching
    // the other forward-only v3-only units in this chain.
  }
}
