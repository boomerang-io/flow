package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes the document fields and the one collection that no v5 entity declares any more, so
 * every stored document maps 1:1 onto its entity class (the v4→v5 entity review found these as
 * unmapped residue that {@code @JsonIgnoreProperties(ignoreUnknown = true)} was silently hiding).
 *
 * <ul>
 *   <li><b>{@code workflows.scope}/{@code ownerRef}</b> and <b>{@code workflow_runs.scope}/{@code
 *       ownerRef}</b> — v3-migration hand-off fields written by {@code _0009__V3MigrateWorkflows}
 *       and {@code _0011__V3MigrateRuns} purely so {@code _0012__V3BuildRelationshipGraph} could
 *       resolve each document's owning workspace. Ownership lives in {@code rel_edges} from
 *       {@code _0012} onwards; the legacy {@code 4002} unit unset them after the graph build and
 *       this chain did not.
 *   <li><b>{@code users.flowTeamRefs}</b> — the same hand-off, from {@code
 *       _0008__V3MigrateUsers} to {@code _0012}'s {@code memberOf} edges. {@code UserEntity} has
 *       no such field.
 *   <li><b>{@code approver_groups.workspaceRef}</b> — written by {@code
 *       _0007__V3MigrateWorkspaces} as a "discoverability" convenience; {@code
 *       ApproverGroupEntity} has no such field and the group→workspace ownership is a {@code
 *       rel_edges} edge.
 *   <li><b>{@code task_runs}/{@code workflow_runs}: {@code agentRef} and {@code dispatcherRef}</b>
 *       — the claim owner is recorded once, on {@code claim.by}. {@code dispatcherRef} was a
 *       byte-for-byte duplicate of it (written and cleared together on every claim/requeue) and
 *       has been removed from both run entities; {@code agentRef} is the pre-DD-06 spelling on
 *       v4 documents ({@code _0015__DispatcherRename} no longer renames it - see that unit).
 *   <li><b>{@code event_queue}</b> collection — the v4 engine's {@code EventQueueEntity} (a
 *       queued raw HTTP request per outbound status CloudEvent), superseded by {@code
 *       events_outbox}/{@code events_inbox}. The entity was deleted with no reader and no TTL, so
 *       the collection would otherwise sit orphaned forever.
 * </ul>
 *
 * <p>Every {@code $unset} is filtered on the field(s) still existing, so it touches only the
 * documents that carry the residue and is a no-op on a second run. On a v3/v4 upgrade this is one
 * pass over the runs that carried a claim owner - the same class of write {@code
 * _0016__WorkspaceRename}'s annotation-key rename already performs. Ungated: fresh installs simply
 * find nothing.
 */
@Change(id = "0031-drop-orphaned-fields-and-collections", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0031__DropOrphanedFieldsAndCollections {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0031__DropOrphanedFieldsAndCollections.class);

  private static final String EVENT_QUEUE_COLLECTION = "event_queue";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    long workflows = unset(db, names.resolve("workflows"), "scope", "ownerRef");
    long workflowRunOwners = unset(db, names.resolve("workflow_runs"), "scope", "ownerRef");
    long users = unset(db, names.resolve("users"), "flowTeamRefs");
    long approverGroups = unset(db, names.resolve("approver_groups"), "workspaceRef");
    long taskRunOwners = unset(db, names.resolve("task_runs"), "agentRef", "dispatcherRef");
    long workflowRunClaimants =
        unset(db, names.resolve("workflow_runs"), "agentRef", "dispatcherRef");
    boolean eventQueueDropped = dropIfPresent(db, names.resolve(EVENT_QUEUE_COLLECTION));
    LOG.info(
        "Orphaned-field cleanup — workflows(scope/ownerRef): {}, workflow_runs(scope/ownerRef): {},"
            + " users(flowTeamRefs): {}, approver_groups(workspaceRef): {},"
            + " task_runs(agentRef/dispatcherRef): {}, workflow_runs(agentRef/dispatcherRef): {},"
            + " event_queue dropped: {}",
        workflows,
        workflowRunOwners,
        users,
        approverGroups,
        taskRunOwners,
        workflowRunClaimants,
        eventQueueDropped);
  }

  private long unset(MongoDatabase db, String collection, String... fields) {
    List<Bson> exists = new ArrayList<>();
    List<Bson> unsets = new ArrayList<>();
    for (String field : fields) {
      exists.add(Filters.exists(field));
      unsets.add(Updates.unset(field));
    }
    MongoCollection<Document> target = db.getCollection(collection);
    return target.updateMany(Filters.or(exists), Updates.combine(unsets)).getModifiedCount();
  }

  private boolean dropIfPresent(MongoDatabase db, String collection) {
    List<String> existing = new ArrayList<>();
    db.listCollectionNames().into(existing);
    if (!existing.contains(collection)) {
      return false;
    }
    long count = db.getCollection(collection).countDocuments();
    db.getCollection(collection).drop();
    LOG.info("Dropped orphaned collection {} ({} document(s) discarded)", collection, count);
    return true;
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Field removal and the collection drop are not reversible - the values were unmapped residue
    // with no reader, so there is nothing to restore.
  }
}
