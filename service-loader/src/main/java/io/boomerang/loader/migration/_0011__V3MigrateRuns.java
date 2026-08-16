package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Migrates the three v3 execution-history collections into their v5 shapes: {@code
 * workflows_activity} (18093 documents on the verified real dump) -> {@code workflow_runs} ({@code
 * WorkflowRunEntity}), {@code workflows_activity_approval} (8 documents) -> {@code actions} ({@code
 * ActionEntity}), {@code workflows_schedules} (90 documents) -> {@code workflow_schedules} ({@code
 * WorkflowScheduleEntity}). Squashes legacy {@code 4002}/{@code 4003}/{@code 4017}. This unit does
 * NOT write {@code rel_nodes}/{@code rel_edges} - Batch E owns the relationship graph.
 *
 * <p><b>{@code workflow_runs} mapping, verified against a real v3 dump and against {@code
 * WorkflowRunEntity}:</b>
 *
 * <ul>
 *   <li>{@code _id} preserved verbatim.
 *   <li>{@code labels} <- v3 {@code labels[]} -\> {@code Map<String,String>} (matching every other
 *       squashed unit); {@code annotations} <- {@code {"boomerang#io/generation":"3",
 *       "boomerang#io/kind":"WorkflowRun"}} ({@code 4002}).
 *   <li>{@code workflowRef}/{@code workflowRevisionRef} <- v3 {@code workflowId}/{@code
 *       workflowRevisionid} (already plain strings in v3, not {@code ObjectId}s - verified).
 *   <li>{@code workflowVersion} <- v3 {@code workflowRevisionVersion}, narrowed to {@code Integer}
 *       - a clean 1:1 field match beyond the batch's literal squash list, kept because {@code
 *       WorkflowRunEntity} declares exactly this field and the real data always carries it.
 *   <li>{@code status} <- v3 {@code status}: {@code inProgress}->{@code running}, {@code
 *       completed}->{@code succeeded}, {@code failure}->{@code failed} (matches {@link
 *       io.boomerang.common.enums.RunStatus} exactly for every other real value - {@code
 *       cancelled}/{@code invalid} pass straight through); {@code statusOverride} mapped the same
 *       way when present (not observed in the real dump — 0 occurrences — still implemented for
 *       fidelity to the field). {@code phase} is ALWAYS {@code "finalized"} ({@code 4002}) - v3 has
 *       no phase concept, every v3 run is by definition already finished.
 *   <li>{@code statusMessage} <- v3 {@code statusMessage} directly when present (4 real
 *       occurrences), else v3 {@code error.message} when present (2 real occurrences, e.g. {@code
 *       "Workflow execution terminated due to exceeding maxinum workflow duration."}) - a value-add
 *       beyond the literal squash list ({@code WorkflowRunEntity.statusMessage} exists and both v3
 *       sources carry human-readable failure detail worth not losing).
 *   <li>{@code trigger} <- v3 {@code trigger}, with {@code scheduler}->{@code schedule} (matching
 *       the same rename {@code 4026} applies to {@code workflows.triggers.scheduler} - kept for
 *       consistency, since {@code WorkflowRunEntity.trigger} is a free-form {@code String}, not
 *       constrained to an enum); {@code manual}/{@code custom}/{@code webhook} pass straight
 *       through.
 *   <li>{@code initiatedByRef} <- v3 {@code initiatedByUserId}, else {@code
 *       initiatedByUserName}, else absent. <b>THE {@code 4002} FIX</b>: legacy computes this exact
 *       value into a local variable ({@code initiatedByRef}) and then NEVER WRITES IT anywhere -
 *       the local is simply discarded. {@code WorkflowRunEntity.initiatedByRef} exists in v5; this
 *       unit writes it. Verified against the real dump: 176 of 18093 runs carry an
 *       {@code initiatedByUserId} and land a non-null {@code initiatedByRef}; the remaining 17917
 *       (scheduler/system-triggered) correctly get none (left absent, not legacy's {@code ""}
 *       empty-string default — {@code null} is the more idiomatic v5 shape for "no initiator").
 *   <li>{@code creationDate} <- v3 {@code creationDate}; {@code startTime} <- v3 {@code startTime}
 *       when present, else {@code creationDate} (v3 never actually carries its own {@code
 *       startTime} field - verified across all 18093 real documents - so this always resolves to
 *       {@code creationDate} on real data, matching {@code 4002}).
 *   <li>{@code duration} <- v3 {@code duration} (a {@code Long}), defaulting to {@code 0} when
 *       absent (matches {@code 4002} and {@code WorkflowRunEntity.duration}'s own {@code long}
 *       primitive default).
 *   <li>{@code isAwaitingApproval} <- v3 {@code isAwaitingApproval}, passthrough.
 *   <li>{@code params[]} <- v3 {@code properties[]} -\> {@code {name, value}} ({@code 4002});
 *       {@code results[]} <- v3 {@code outputProperties[]} -\> {@code {name, value}} ({@code
 *       4002}) - always empty on the real dump (0 occurrences) but implemented per the field
 *       mapping regardless.
 *   <li>{@code timeout}/{@code retries}/{@code dispatcherRef}/{@code workspaces} - no v3 source,
 *       left unset/default.
 *   <li><b>Extra fields, undeclared by {@code WorkflowRunEntity}, kept for Batch E</b> (same
 *       technique as {@code _0023}'s {@code scope}/{@code ownerRef} on {@code workflows} — see that
 *       unit's javadoc for the DD-08 rationale): {@code scope} (v3's raw value; only {@code
 *       system}/{@code user} appear in the real dump - zero {@code team}-scope runs exist here) and
 *       {@code ownerRef} ({@code teamId} when {@code scope=team}, {@code userId} when {@code
 *       scope=user}). {@code 4002} builds a {@code belongs-to} relationship from these fields and
 *       then removes them; this unit does not write the relationship (out of scope, Batch E's job)
 *       but must not destroy the ownership fact needed to build it later, since Batch E cannot read
 *       the original v3-shaped {@code workflows_activity} at all - it is dropped by this same unit.
 * </ul>
 *
 * <p><b>Performance.</b> 18093 documents - migrated via {@code ReplaceOneModel} (upsert) {@code
 * bulkWrite} batches of {@value #BATCH_SIZE}, unordered (independent per-document, no ordering
 * dependency between runs). Upsert makes each batch idempotent by construction — replaying an
 * already-applied batch after a crash mid-run simply rewrites the same v5 document — without
 * needing a per-document existence round-trip (18093 of those would be the real performance risk).
 *
 * <p><b>{@code actions} mapping</b> (8 real documents, matches {@code ActionEntity}): {@code
 * workflowRef}/{@code workflowRunRef}/{@code taskRunRef} <- v3 {@code workflowId}/{@code
 * activityId}/{@code taskActivityId} ({@code 4003}); {@code type}: v3 {@code task}->{@code manual},
 * else passthrough ({@code approval} observed); {@code status} passthrough ({@code
 * approved}/{@code submitted}/{@code rejected} all match {@link
 * io.boomerang.common.enums.ActionStatus} exactly); {@code actioners[]} <- v3 {@code
 * actioners[]} with {@code actionDate}->{@code date} ({@code approverId}/{@code comments}/{@code
 * approved} passthrough - {@code 4003} mutates whole documents in place, so nothing here was ever
 * lost, verified against the real dump); {@code numberOfApprovers}/{@code creationDate}
 * passthrough; {@code instructions}/{@code approverGroupRef} - no v3 source, left unset.
 *
 * <p><b>{@code workflow_schedules} mapping</b> (90 real documents, matches {@code
 * WorkflowScheduleEntity}): {@code _id} preserved verbatim (also true for the 14 real documents
 * from an older v3 schedule shape whose OWN {@code _id} happens to equal the workflow's {@code
 * _id} — a harmless historical coincidence with no other collection referencing it); {@code
 * workflowRef} <- v3 {@code workflowId} when present, else the schedule document's own {@code _id}
 * (covers those same 14 older-shape documents, which have no separate {@code workflowId} field at
 * all); {@code name}/{@code description}/{@code timezone}/{@code dateSchedule}/{@code type}/{@code
 * status} passthrough (v3 {@code type} values {@code runOnce}/{@code cron}/{@code advancedCron} and
 * {@code status} values {@code active}/{@code inactive}/{@code trigger_disabled}/{@code
 * completed}/{@code deleted} all match {@link io.boomerang.common.enums.WorkflowScheduleType}/
 * {@link io.boomerang.common.enums.WorkflowScheduleStatus} exactly - no mapping needed); {@code
 * labels[]} -\> map. <b>TWO {@code 4017} FIXES</b>: (1) {@code cronSchedule} <- v3 {@code
 * cronSchedule} when present, else v3's typo'd {@code cronSchedlue} (the 14 older-shape documents
 * only ever carry the typo'd key) - legacy instead did {@code remove("properties")} while the real
 * source array is {@code parameters}, leaving the OLD key behind entirely undetected; building a
 * fresh v5 document here makes that whole bug moot (neither stray key can survive since nothing
 * copies it forward). (2) {@code params[]} <- v3 {@code parameters[]} -\> {@code {name, value,
 * type}} with {@code type} hardcoded to the literal string {@code "string"} - legacy wrote {@code
 * param.put("type", parameter.get("string"))}, reading the literal absent key {@code "string"}
 * instead of assigning the literal value {@code "string"}, so {@code type} was {@code null} on
 * every real v4 install. {@code schedulerRef}/{@code nextFireAt}/{@code lastFiredAt}/{@code
 * retryCount} - no v3 source (JobRunr/the claim-based watcher are v5-only concepts), left
 * unset/default.
 */
@Change(id = "0011-v3-migrate-runs", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0011__V3MigrateRuns {

  private static final Logger LOG = LoggerFactory.getLogger(_0011__V3MigrateRuns.class);
  private static final int BATCH_SIZE = 1000;

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — runs/actions/schedules already migrated (or never existed).");
      return;
    }

    migrateWorkflowRuns(db, names);
    migrateActions(db, names);
    migrateSchedules(db, names);
  }

  // =====================================================================================
  // workflows_activity -> workflow_runs
  // =====================================================================================

  private void migrateWorkflowRuns(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> source = db.getCollection(names.resolve("workflows_activity"));
    MongoCollection<Document> target = db.getCollection(names.resolve("workflow_runs"));

    long total = 0;
    List<WriteModel<Document>> batch = new ArrayList<>(BATCH_SIZE);
    for (Document run : source.find().batchSize(BATCH_SIZE)) {
      batch.add(
          new ReplaceOneModel<>(
              Filters.eq("_id", run.getObjectId("_id")), migrateRun(run), new ReplaceOptions().upsert(true)));
      total++;
      if (batch.size() >= BATCH_SIZE) {
        target.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      target.bulkWrite(batch, new BulkWriteOptions().ordered(false));
    }

    source.drop();
    LOG.info("v3 workflows_activity migrated to v5 workflow_runs — {} migrated, workflows_activity dropped", total);
  }

  private Document migrateRun(Document source) {
    Document run = new Document();
    run.put("_id", source.getObjectId("_id"));
    run.put("labels", convertLabels(source.get("labels")));
    run.put(
        "annotations",
        new Document("boomerang#io/generation", "3").append("boomerang#io/kind", "WorkflowRun"));

    Date creationDate = source.getDate("creationDate");
    run.put("creationDate", creationDate);
    Date startTime = source.getDate("startTime");
    run.put("startTime", startTime != null ? startTime : creationDate);
    Long duration = toLong(source.get("duration"));
    run.put("duration", duration != null ? duration : 0L);
    run.put("isAwaitingApproval", source.getBoolean("isAwaitingApproval", Boolean.FALSE));

    run.put("workflowRef", asString(source.get("workflowId")));
    run.put("workflowRevisionRef", asString(source.get("workflowRevisionid")));
    Integer workflowVersion = toInt(source.get("workflowRevisionVersion"));
    if (workflowVersion != null) {
      run.put("workflowVersion", workflowVersion);
    }

    String status = mapRunStatus(source.getString("status"));
    run.put("status", status != null ? status : "failed");
    run.put("phase", "finalized");
    String statusOverride = source.getString("statusOverride");
    if (statusOverride != null) {
      run.put("statusOverride", mapRunStatus(statusOverride));
    }
    String statusMessage = source.getString("statusMessage");
    if (statusMessage == null) {
      Document error = (Document) source.get("error");
      if (error != null) {
        statusMessage = error.getString("message");
      }
    }
    if (statusMessage != null) {
      run.put("statusMessage", statusMessage);
    }

    String trigger = source.getString("trigger");
    if (trigger != null) {
      run.put("trigger", "scheduler".equals(trigger) ? "schedule" : trigger);
    }

    // THE 4002 FIX - see the class javadoc.
    String initiatedByRef = source.getString("initiatedByUserId");
    if (initiatedByRef == null) {
      initiatedByRef = source.getString("initiatedByUserName");
    }
    if (initiatedByRef != null) {
      run.put("initiatedByRef", initiatedByRef);
    }

    run.put("params", migrateRunParams(source.get("properties")));
    run.put("results", migrateRunResults(source.get("outputProperties")));

    // See the class javadoc's "Extra fields" bullet.
    String scope = source.getString("scope");
    if (scope != null) {
      run.put("scope", scope);
      String ownerRef =
          "team".equals(scope) ? source.getString("teamId") : "user".equals(scope) ? source.getString("userId") : null;
      if (ownerRef != null) {
        run.put("ownerRef", ownerRef);
      }
    }
    return run;
  }

  private String mapRunStatus(String v3Status) {
    if (v3Status == null) {
      return null;
    }
    return switch (v3Status) {
      case "inProgress" -> "running";
      case "completed" -> "succeeded";
      case "failure" -> "failed";
      default -> v3Status;
    };
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateRunParams(Object rawProperties) {
    List<Document> params = new LinkedList<>();
    if (rawProperties instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Document p) {
          params.add(new Document("name", p.get("key")).append("value", p.get("value")));
        }
      }
    }
    return params;
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateRunResults(Object rawOutputProperties) {
    List<Document> results = new LinkedList<>();
    if (rawOutputProperties instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Document p) {
          results.add(new Document("name", p.get("key")).append("value", p.get("value")));
        }
      }
    }
    return results;
  }

  // =====================================================================================
  // workflows_activity_approval -> actions
  // =====================================================================================

  private void migrateActions(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> source = db.getCollection(names.resolve("workflows_activity_approval"));
    MongoCollection<Document> target = db.getCollection(names.resolve("actions"));

    long migrated = 0;
    for (Document approval : source.find().into(new ArrayList<>())) {
      ObjectId id = approval.getObjectId("_id");
      if (target.find(Filters.eq("_id", id)).first() == null) {
        target.insertOne(migrateAction(approval, id));
        migrated++;
      }
    }

    source.drop();
    LOG.info(
        "v3 workflows_activity_approval migrated to v5 actions — {} migrated, "
            + "workflows_activity_approval dropped",
        migrated);
  }

  private Document migrateAction(Document approval, ObjectId id) {
    Document action = new Document();
    action.put("_id", id);
    action.put("workflowRef", asString(approval.get("workflowId")));
    action.put("workflowRunRef", asString(approval.get("activityId")));
    action.put("taskRunRef", asString(approval.get("taskActivityId")));
    action.put("actioners", migrateActioners(approval.get("actioners")));
    action.put("status", approval.getString("status"));
    String type = approval.getString("type");
    action.put("type", "task".equals(type) ? "manual" : type);
    action.put("creationDate", approval.getDate("creationDate"));
    Integer numberOfApprovers = toInt(approval.get("numberOfApprovers"));
    action.put("numberOfApprovers", numberOfApprovers != null ? numberOfApprovers : 0);
    return action;
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateActioners(Object raw) {
    List<Document> result = new LinkedList<>();
    if (raw instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Document a) {
          Document actioner = new Document();
          actioner.put("approverId", a.get("approverId"));
          actioner.put("comments", a.get("comments"));
          actioner.put("date", a.get("actionDate"));
          actioner.put("approved", a.getBoolean("approved", Boolean.FALSE));
          result.add(actioner);
        }
      }
    }
    return result;
  }

  // =====================================================================================
  // workflows_schedules -> workflow_schedules
  // =====================================================================================

  private void migrateSchedules(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> source = db.getCollection(names.resolve("workflows_schedules"));
    MongoCollection<Document> target = db.getCollection(names.resolve("workflow_schedules"));

    long migrated = 0;
    for (Document schedule : source.find().into(new ArrayList<>())) {
      ObjectId id = schedule.getObjectId("_id");
      if (target.find(Filters.eq("_id", id)).first() == null) {
        target.insertOne(migrateSchedule(schedule, id));
        migrated++;
      }
    }

    source.drop();
    LOG.info(
        "v3 workflows_schedules migrated to v5 workflow_schedules — {} migrated, workflows_schedules dropped",
        migrated);
  }

  private Document migrateSchedule(Document schedule, ObjectId id) {
    Document doc = new Document();
    doc.put("_id", id);
    Object workflowId = schedule.get("workflowId");
    doc.put("workflowRef", workflowId != null ? workflowId.toString() : id.toString());
    doc.put("name", schedule.getString("name"));
    doc.put("description", schedule.getString("description"));
    Date creationDate = schedule.getDate("creationDate");
    doc.put("creationDate", creationDate != null ? creationDate : new Date());
    doc.put("type", schedule.getString("type"));
    doc.put("status", schedule.getString("status"));
    doc.put("labels", convertLabels(schedule.get("labels")));
    // FIX 1 (legacy 4017): a fresh v5 document is built from scratch, so neither the "properties"
    // stray key nor the "cronSchedlue" typo can ever survive - see the class javadoc.
    String cronSchedule = schedule.getString("cronSchedule");
    if (cronSchedule == null) {
      cronSchedule = schedule.getString("cronSchedlue");
    }
    doc.put("cronSchedule", cronSchedule);
    doc.put("dateSchedule", schedule.getDate("dateSchedule"));
    doc.put("timezone", schedule.getString("timezone"));
    doc.put("params", migrateScheduleParams(schedule.get("parameters")));
    return doc;
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateScheduleParams(Object raw) {
    List<Document> result = new LinkedList<>();
    if (raw instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Document p) {
          Document param = new Document();
          param.put("name", p.get("key"));
          param.put("value", p.get("value"));
          // FIX 2 (legacy 4017): hardcode the literal "string", rather than reading the absent
          // literal key "string" - see the class javadoc.
          param.put("type", "string");
          result.add(param);
        }
      }
    }
    return result;
  }

  // =====================================================================================
  // helpers
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private Map<String, String> convertLabels(Object rawLabels) {
    Map<String, String> labels = new HashMap<>();
    if (rawLabels instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Document label) {
          labels.put(label.getString("key"), label.getString("value"));
        }
      }
    }
    return labels;
  }

  private static Integer toInt(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    return Integer.valueOf(value.toString());
  }

  private static Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    return Long.valueOf(value.toString());
  }

  private static String asString(Object value) {
    return value != null ? value.toString() : null;
  }

  @Rollback
  public void rollback() {
    // Source collections are dropped once migrated - not restorable, matching the other
    // forward-only v3-only units in this chain.
  }
}
