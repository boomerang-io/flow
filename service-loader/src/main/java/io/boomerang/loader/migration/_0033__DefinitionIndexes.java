package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * The definition-side lookup indexes that, until now, existed only as {@code @Indexed}/{@code
 * @CompoundIndex} annotations on the {@code lib-common} entities. Those annotations are inert
 * ({@code spring.data.mongodb.auto-index-creation=false} - the loader is the sole index
 * authority), and no earlier unit created their equivalents, so on a fresh v5 install every one of
 * these lookups was a collection scan. A v4 upgrade DOES carry them (v4 built them at boot), so
 * each is created via {@link MigrationUtils#ensureIndexKeys}: an existing index with the same keys
 * - whatever Spring named it - is kept and reported instead of duplicated.
 *
 * <ul>
 *   <li>{@code workflows.name_lookup {name:1}} - {@code WorkflowEntity.name @Indexed} parity.
 *   <li>{@code workflow_revisions.workflow_ref_version {workflowRef:1, version:1}} - {@code
 *       workflow_ref_version_idx} parity; serves every {@code WorkflowRevisionRepository} finder
 *       ({@code workflowRef} alone by prefix, {@code (workflowRef, version)} exact, latest =
 *       {@code workflowRef} + sort {@code version desc}).
 *   <li>{@code workflow_templates.name_version {name:1, version:1}} - {@code WorkflowTemplateEntity}
 *       {@code name}/{@code version} parity, as the compound the finders actually use ({@code
 *       findByNameAndVersion}, latest-by-name, {@code deleteAllByName}, {@code name in (...)}).
 *   <li>{@code tasks.name_lookup {name:1}} - {@code TaskEntity.name @Indexed} parity ({@code
 *       existsByName}/{@code findByName}/{@code countByNameAndStatus}/{@code deleteByName}, the
 *       catalogue query's {@code name in (...)}).
 *   <li>{@code task_revisions.parent_ref_version {parentRef:1, version:1}} - NEW (no v4
 *       annotation either): every {@code TaskRevisionRepository} finder is by {@code parentRef}
 *       (+ {@code version}), on the DAG-build hot path.
 *   <li>{@code workflow_schedules.fire_sweep {status:1, nextFireAt:1}} - the {@code
 *       ScheduleWatcher} sweeps ({@code findByStatusAndNextFireAtLessThanEqual}, {@code
 *       findByStatusAndNextFireAtIsNull}) run every 10s; {@code nextFireAt}'s {@code
 *       @Indexed(sparse=true)} annotation was never built anywhere.
 *   <li>{@code workflow_schedules.workflow_lookup {workflowRef:1}} - {@code findByWorkflowRef} /
 *       {@code findByWorkflowRefInAndStatusIn}.
 * </ul>
 *
 * <p>Deliberately NOT created: {@code workflow_schedules.schedulerRef} (legacy JobRunr job id,
 * never written any more) and {@code task_runs {status, phase}} (the v4 {@code
 * status_phase_type_idx} - no query filters on {@code status}/{@code phase} without {@code type}
 * or {@code workflowRunRef} in front, which {@code _0017__RunIndexes}'s {@code claim_page}/{@code
 * run_tasks} already serve). All non-unique, so best-effort per {@link MigrationUtils#ensureIndex}.
 */
@Change(id = "0033-definition-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0033__DefinitionIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    ensureIndexKeys(
        db, names.resolve("workflows"), "name_lookup", new Document("name", 1), new IndexOptions());
    ensureIndexKeys(
        db,
        names.resolve("workflow_revisions"),
        "workflow_ref_version",
        new Document("workflowRef", 1).append("version", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        names.resolve("workflow_templates"),
        "name_version",
        new Document("name", 1).append("version", 1),
        new IndexOptions());
    ensureIndexKeys(
        db, names.resolve("tasks"), "name_lookup", new Document("name", 1), new IndexOptions());
    ensureIndexKeys(
        db,
        names.resolve("task_revisions"),
        "parent_ref_version",
        new Document("parentRef", 1).append("version", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        names.resolve("workflow_schedules"),
        "fire_sweep",
        new Document("status", 1).append("nextFireAt", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        names.resolve("workflow_schedules"),
        "workflow_lookup",
        new Document("workflowRef", 1),
        new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("workflows"), "name_lookup");
    dropIndex(db, names.resolve("workflow_revisions"), "workflow_ref_version");
    dropIndex(db, names.resolve("workflow_templates"), "name_version");
    dropIndex(db, names.resolve("tasks"), "name_lookup");
    dropIndex(db, names.resolve("task_revisions"), "parent_ref_version");
    dropIndex(db, names.resolve("workflow_schedules"), "fire_sweep");
    dropIndex(db, names.resolve("workflow_schedules"), "workflow_lookup");
  }
}
