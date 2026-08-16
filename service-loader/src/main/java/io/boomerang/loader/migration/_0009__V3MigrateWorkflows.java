package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
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
 * V3-only. Single pass: v3 {@code workflows} (67 documents on the verified real dump) reshaped in
 * place -> v5 {@code WorkflowEntity}, plus v3 {@code workflows_revisions} (151 documents) -> v5
 * {@code workflow_revisions} ({@code WorkflowRevisionEntity}), written directly in the v5 shape.
 *
 * <p>This SQUASHES legacy changesets {@code 4005} (the core workflow+DAG->tasks[] reshape),
 * {@code 4013} (revision changelog {@code userId}->{@code author}, {@code userName} dropped —
 * folded straight into the single revision build, same as {@code userName} being PII-dropped
 * everywhere else in this program), {@code 4021} (description<-shortDescription when empty),
 * {@code 4026} (triggers {@code enable}->{@code enabled}+{@code conditions[]}, {@code
 * scheduler}->{@code schedule}, added {@code event} trigger), {@code 4034} (the {@code
 * templateRef}/{@code taskVersion} resolution — <b>with the v4 bug fixed</b>, see below), {@code
 * 4042} (revision config/params merge — reproduces {@code _0006__V3MigrateTaskCatalogue#mergeParams}'s
 * algorithm, "same as {@code _0022} did for task revisions" per the batch instructions), {@code
 * 4047} (single-pass {@code name}(display)-\>{@code displayName} + slugified {@code name}, reusing
 * {@code _0022}'s exact slug algorithm) and {@code 4048} (run-workflow task param {@code
 * workflowId}-\>{@code workflowRef}).
 *
 * <p><b>THE HEADLINE FIX (batch instructions, verified against the real dump).</b> Legacy {@code
 * 4005} does {@code task.replace("templateVersion", (Integer) dagTask.get("templateVersion"))} on
 * a Document constructed two lines earlier with {@code new Document()} — {@link
 * Document#replace(Object, Object)} is a no-op when the key does not already exist, so {@code
 * templateVersion} was NEVER actually written onto the migrated task. Legacy {@code 4034} then
 * reads {@code wfTask.get("taskVersion")} — the NEW, still-absent key — and writes it right back
 * to itself, another no-op. Net effect on every real v4 install: {@code taskVersion} is {@code
 * null} on every task, forever (unrecoverable there — the source {@code templateVersion} value is
 * gone). This unit carries {@code dagTask.get("templateVersion")} straight onto {@code
 * task.taskVersion} in a single step (see {@link #migrateTask}) — verified against the real dump:
 * all 287 real dag tasks that carry a {@code templateId} also carry a {@code templateVersion}, and
 * every one lands non-null.
 *
 * <p><b>{@code templateRef}-\>{@code taskRef} resolution.</b> The batch instructions describe
 * resolving the task NAME to the task {@code _id}, matching legacy {@code 4034}'s own two-hop
 * lookup ({@code task_templates} by id -\> name, then {@code tasks} by name -\> id). That
 * intermediate is unreachable here: {@code task_templates} no longer exists by the time this unit
 * runs (Batch B - {@code _0006__V3MigrateTaskCatalogue} - has already dropped it), and {@code _0022}
 * documents that it preserves {@code tasks._id} verbatim from {@code task_templates._id}. So {@code
 * dagTask.templateId} (a v3 {@code task_templates} id) already equals the migrated task's {@code
 * _id} directly — no name hop needed, and none is possible any more. This unit resolves {@code
 * taskRef} by using {@code templateId} directly (verified to exist in {@code tasks} - logged, not
 * fatal, if not: non-destructive, the same "log and carry through" posture {@code 4034} itself
 * took on a lookup miss).
 *
 * <p><b>Field mapping, verified against a real v3 dump and against {@code WorkflowEntity}/{@code
 * WorkflowRevisionEntity}/{@code WorkflowTask}/{@code WorkflowTaskDependency}:</b>
 *
 * <ul>
 *   <li>{@code workflows._id} preserved verbatim.
 *   <li>{@code workflows.displayName} <- v3 {@code name}; {@code workflows.name} <- that slugified
 *       with {@code _0022}'s exact algorithm ({@code trim().toLowerCase().replace(' ', '-')}) -
 *       {@code 4047} folded in, single pass (never an intermediate display-name-as-name state a
 *       later unit would need to fix).
 *   <li>{@code workflows.description} <- v3 {@code description} when non-empty, else {@code
 *       shortDescription} ({@code 4021}); {@code shortDescription} itself is dropped (no {@code
 *       WorkflowEntity} field).
 *   <li>{@code workflows.status} <- v3 {@code status}, passed straight through (values on the real
 *       dump - {@code active}/{@code deleted} - match {@link
 *       io.boomerang.common.enums.WorkflowStatus} exactly).
 *   <li>{@code workflows.icon} <- v3 {@code icon}, passed straight through.
 *   <li>{@code workflows.labels} <- v3 {@code labels[]} ({@code {key,value}} documents) -\> {@code
 *       Map<String,String>}, matching every other squashed unit's label-array convention.
 *   <li>{@code workflows.annotations} <- {@code {"boomerang#io/generation":"3",
 *       "boomerang#io/kind":"Workflow"}} (the {@code #}-for-{@code .} escaping matches {@code
 *       _0022}/{@code _0027}/{@code _0028}) - v3 has no equivalent.
 *   <li>{@code workflows.creationDate} <- the version-1 revision's {@code changelog.date} (matches
 *       {@code 4005}: "Set Creation Date from first revisions changelog"; verified - all 67 real
 *       workflows have a version-1 revision with a changelog). Falls back to {@code new Date()}
 *       only for the data-quality edge case where no version-1 revision exists at all (not observed
 *       on this dump).
 *   <li>{@code workflows.triggers} <- {@link #migrateTriggers}: {@code manual}/{@code
 *       scheduler}(-\>{@code schedule})/{@code webhook} each become {@code {enabled, conditions:
 *       []}} ({@code enable}-\>{@code enabled}; {@code 4026}); {@code event} is always added
 *       disabled with no conditions ({@code 4026}); v3's {@code dockerhub}/{@code slack}/{@code
 *       custom} trigger keys have no v5 equivalent and are dropped; {@code github} has no v3 source
 *       and is left absent (reads back as {@link io.boomerang.common.model.WorkflowTrigger}'s own
 *       {@code Trigger(false)} default on a fresh entity read - the field default is not present in
 *       this Document because {@code MappingMongoConverter} only overwrites fields actually present
 *       in the source document). Unlike {@code 4026} (which skips the whole trigger rewrite when
 *       v3 {@code triggers} is entirely absent - not observed on this dump, but a latent legacy
 *       gap), this unit always writes a complete, valid v5 {@code triggers} document.
 *   <li><b>{@code workflows.workspaces} does NOT exist</b> - despite the {@code storage}-\>{@code
 *       workspaces[]} description in the batch brief, {@code WorkflowEntity} has no such field;
 *       {@code WorkflowRevisionEntity} does. Legacy {@code 4005} itself computes {@code workspaces}
 *       from the WORKFLOW's {@code storage} field but writes it onto every REVISION document, never
 *       onto the workflow - verified against the real code and reproduced faithfully here (see
 *       {@link #buildWorkspaces}, applied identically to every revision of the same workflow, since
 *       {@code storage} is workflow-level data with no per-revision variant in v3).
 *   <li><b>Extra fields, undeclared by {@code WorkflowEntity}, kept for two consumers within this
 *       migration program</b> (not part of the v5 API surface - {@code MappingMongoConverter}
 *       silently drops any Mongo field a target Java class does not declare, so these are invisible
 *       to every application code path): {@code scope} (v3's raw {@code system}/{@code
 *       team}/{@code user}/{@code template} value) and {@code ownerRef} (v3 {@code flowTeamId} when
 *       {@code scope=team}, {@code ownerUserId} when {@code scope=user}, absent otherwise) - a
 *       condensed replacement for the raw {@code flowTeamId}/{@code ownerUserId} fields the batch
 *       instructions say to drop (dropped BY NAME - the WorkflowEntity shape never carries them -
 *       while the ownership fact itself survives under DD-08-compliant typed fields, never an
 *       annotation). Two consumers: (1) THIS SAME BATCH's {@code
 *       _0010__V3ExtractWorkflowTemplates}, which depends on finding {@code scope=template}
 *       workflows AFTER this unit has already reshaped them (it runs immediately after, in the same
 *       chain); (2) Batch E's relationship-graph build, which depends on B/C/D and therefore cannot
 *       read the original v3-shaped {@code workflows}/{@code flowTeamId}/{@code ownerUserId} at
 *       all - by the time it runs, this unit has already replaced every v3 document. Mirrors the
 *       same discoverability technique {@code _0027}/{@code _0028} use ({@code workspaceRef}/{@code
 *       externalRef}).
 * </ul>
 *
 * <p><b>{@code workflow_revisions} field mapping:</b>
 *
 * <ul>
 *   <li>{@code _id} preserved verbatim (this is what lets {@code _0024} identify the exact
 *       extracted document later using the SAME id the real dump's seeded templates already use -
 *       verified: the real dump's template-scope workflows' v1 revision ids, {@code
 *       62be6a3266ff43491f09d2e8} and {@code 62be6a3e66ff43491f09d2ea}, are EXACTLY the two ids
 *       {@code _0023__SeedTemplates}'s collision guard names).
 *   <li>{@code workflowRef} <- v3 {@code workFlowId} (a v3 string, already the workflow's {@code
 *       _id.toString()}).
 *   <li>{@code version} <- v3 {@code version} (a v3 {@code Long}), narrowed to {@code Integer}.
 *   <li>{@code tasks[]} <- {@link #migrateTasks} (see below).
 *   <li>{@code workspaces} <- {@link #buildWorkspaces} of the OWNING WORKFLOW's {@code storage} (see
 *       the {@code workflows.workspaces} bullet above for why this lands here, not on {@code
 *       workflows}).
 *   <li>{@code changelog} <- {@code {author: revision.changelog.userId, reason:
 *       revision.changelog.reason, date: revision.changelog.date}} - {@code userName} is DROPPED
 *       (PII; {@code 4013}). Every one of the 151 real revisions carries a changelog (verified).
 *   <li>{@code params[]} <- {@link #mergeWorkflowParams}, applied per-revision against a FRESH
 *       deep copy of the workflow's {@code properties[]} every time (see the method javadoc for why
 *       this matters - a genuine squashing hazard the multi-changeset original never had).
 *   <li>{@code markdown}/{@code timeout}/{@code retries} - no v3 source, left unset.
 * </ul>
 *
 * <p><b>{@code tasks[]} mapping</b> (per DAG task, {@link #migrateTask}):
 *
 * <ul>
 *   <li>{@code start}/{@code end} nodes: {@code name} hardcoded to {@code "start"}/{@code "end"}
 *       (v3 never labels them); no {@code taskRef}/{@code taskVersion}/{@code params}/{@code
 *       results}.
 *   <li>every other task: {@code name} <- v3 {@code label}; {@code taskRef}/{@code taskVersion} <-
 *       {@code templateId}/{@code templateVersion} (see the headline fix above - this applies to
 *       EVERY non-start/end v3 task, including native types like {@code decision}/{@code
 *       manual}/{@code approval} - v3's {@code task_templates} carries an entry for native task
 *       types too, matching {@code _0022}'s own javadoc); {@code results} <- v3 {@code results}
 *       (explicit, possibly-null passthrough, matching {@code _0022}'s established convention);
 *       {@code params[]} <- v3 {@code properties[]} -\> {@code {name, value}} ({@link
 *       io.boomerang.common.model.RunParam} shape), with the {@code 4048} fix applied inline: for a
 *       {@code runworkflow}/{@code runscheduledworkflow} task, a param literally named {@code
 *       workflowId} (case-insensitive, matching legacy) is renamed to {@code workflowRef} - verified
 *       against the real dump, which has exactly this param key on its {@code
 *       runworkflow}/{@code runscheduledworkflow} tasks.
 *   <li>{@code type} <- v3 {@code type}, with ONE fix beyond legacy fidelity: v3 dag tasks spell the
 *       custom-task type {@code customtask} (no camel case, 4 real occurrences) - distinct from
 *       {@code task_templates.nodetype}'s {@code customTask} that {@code _0022} maps to {@code
 *       custom}. Legacy {@code 4005} passes the dag task's {@code type} straight through
 *       UNMAPPED, which would write an invalid {@link io.boomerang.common.enums.TaskType} value
 *       forever. This unit maps {@code customtask}-\>{@code custom} (mirroring {@code _0022}'s own
 *       {@code customTask}-\>{@code custom} intent); every other real v3 dag-task type value
 *       ({@code start,end,template,decision,script,runscheduledworkflow,manual,approval,eventwait,
 *       setwfstatus,runworkflow,acquirelock,releaselock}) already matches {@link
 *       io.boomerang.common.enums.TaskType}'s labels exactly and passes through unchanged.
 *   <li>{@code dependencies[]} <- {@link #migrateDependencies}: {@code decisionCondition} <- v3
 *       {@code switchCondition} (default {@code ""}); {@code taskRef} <- the referenced dependency's
 *       v5 task name ({@code "start"}/{@code "end"} for start/end targets, else that task's v3
 *       {@code label} - v3 dag task labels are unique per revision, verified across all 151 real
 *       revisions); {@code taskId}/{@code switchCondition}/{@code conditionalExecution}/{@code
 *       additionalProperties} removed (matching {@code 4005}); {@code metadata} (visual edge-routing
 *       points) and {@code executionCondition} are LEFT ON the dependency document, matching {@code
 *       4005}'s own commented-out {@code dependency.remove("metadata")} - this is not a {@link
 *       io.boomerang.common.model.WorkflowTaskDependency} field, but it is present on real seeded
 *       template data ({@code service-loader/src/main/resources/seed/workflow-templates.json}),
 *       confirming this is intentional, accepted passthrough cruft rather than a bug to fix.
 *   <li>{@code labels} <- {@code {}}; {@code annotations} <- {@code
 *       {"boomerang#io/position": <v3 metadata.position>}} when present (matches {@code 4005}'s
 *       {@code ANNOTATION_PREFIX + "/position"}, escaped the same way as every other annotation key
 *       in this program).
 * </ul>
 *
 * <p>Idempotency: workflows are matched (and only processed) by the v3 {@code _class}
 * discriminator, matching {@code _0027}/{@code _0028} - a document rewritten by a prior run never
 * carries it again. Within one workflow's processing, revisions are migrated FIRST (individually
 * guarded by a {@code (workflowRef, version)} existence check, matching {@code _0022}'s
 * {@code task_revisions} pattern - safe to retry) and the workflow document is rewritten (losing
 * {@code _class}) LAST - so a crash partway through leaves the workflow still {@code
 * _class}-tagged and the next run retries safely, never leaving a workflow "done" with missing
 * revisions. {@code workflows_revisions} is dropped unconditionally once every {@code _class}-tagged
 * workflow has been processed (matching {@code _0022}'s {@code task_templates.drop()} - a no-op on
 * an already-dropped collection).
 */
@Change(id = "0009-v3-migrate-workflows", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0009__V3MigrateWorkflows {

  private static final Logger LOG = LoggerFactory.getLogger(_0009__V3MigrateWorkflows.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — workflows already migrated (or never existed) in v5 shape.");
      return;
    }

    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> legacyRevisions = db.getCollection(names.resolve("workflows_revisions"));
    MongoCollection<Document> revisions = db.getCollection(names.resolve("workflow_revisions"));
    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));

    long workflowsMigrated = 0;
    long revisionsMigrated = 0;

    // "_class" is the v3 discriminator every real workflow document carries.
    for (Document source : workflows.find(Filters.exists("_class")).into(new ArrayList<>())) {
      ObjectId workflowId = source.getObjectId("_id");
      String workflowRef = workflowId.toString();
      List<Document> properties = getList(source, "properties");
      Document storage = (Document) source.get("storage");
      List<Document> workspaces = buildWorkspaces(storage);

      Date v1CreationDate = null;
      for (Document rev :
          legacyRevisions
              .find(Filters.eq("workFlowId", workflowRef))
              .sort(Sorts.ascending("version"))
              .into(new ArrayList<>())) {
        Integer version = toInt(rev.get("version"));
        if (version == null) {
          continue;
        }
        Document changelogSrc = (Document) rev.get("changelog");
        if (version == 1 && changelogSrc != null) {
          v1CreationDate = changelogSrc.getDate("date");
        }
        boolean exists =
            revisions
                    .find(Filters.and(Filters.eq("workflowRef", workflowRef), Filters.eq("version", version)))
                    .first()
                != null;
        if (!exists) {
          revisions.insertOne(
              migrateRevision(rev, workflowRef, version, workspaces, properties, tasks));
          revisionsMigrated++;
        }
      }

      workflows.replaceOne(
          Filters.eq("_id", workflowId), migrateWorkflow(source, workflowId, v1CreationDate));
      workflowsMigrated++;
    }

    legacyRevisions.drop();
    LOG.info(
        "v3 workflows migrated — {} workflows, {} revisions migrated, workflows_revisions dropped",
        workflowsMigrated,
        revisionsMigrated);
  }

  // =====================================================================================
  // workflows
  // =====================================================================================

  private Document migrateWorkflow(Document source, ObjectId workflowId, Date v1CreationDate) {
    Document workflow = new Document();
    workflow.put("_id", workflowId);
    String displayName = source.getString("name");
    workflow.put("displayName", displayName);
    workflow.put("name", slugify(displayName));
    String description = source.getString("description");
    workflow.put(
        "description",
        (description != null && !description.isEmpty()) ? description : source.getString("shortDescription"));
    String status = source.getString("status");
    workflow.put("status", status != null ? status : "active");
    workflow.put("icon", source.get("icon"));
    workflow.put("labels", convertLabels(source.get("labels")));
    workflow.put(
        "annotations",
        new Document("boomerang#io/generation", "3").append("boomerang#io/kind", "Workflow"));
    workflow.put("creationDate", v1CreationDate != null ? v1CreationDate : new Date());
    workflow.put("triggers", migrateTriggers((Document) source.get("triggers")));

    // See the class javadoc's "Extra fields" bullet.
    String scope = source.getString("scope");
    if (scope != null) {
      workflow.put("scope", scope);
      String ownerRef =
          "team".equals(scope)
              ? asString(source.get("flowTeamId"))
              : "user".equals(scope) ? asString(source.get("ownerUserId")) : null;
      if (ownerRef != null) {
        workflow.put("ownerRef", ownerRef);
      }
    }
    return workflow;
  }

  /** {@code 4004}/{@code _0022}'s slugification: {@code trim().toLowerCase().replace(' ', '-')}. */
  private String slugify(String displayName) {
    return displayName.trim().toLowerCase().replace(' ', '-');
  }

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

  /** {@code 4026}'s {@code enable}->{@code enabled}+{@code conditions[]} reshape, always complete. */
  private Document migrateTriggers(Document rawTriggers) {
    Document manualSrc = rawTriggers != null ? (Document) rawTriggers.get("manual") : null;
    Document schedulerSrc = rawTriggers != null ? (Document) rawTriggers.get("scheduler") : null;
    Document webhookSrc = rawTriggers != null ? (Document) rawTriggers.get("webhook") : null;
    Document triggers = new Document();
    triggers.put("manual", triggerDoc(manualSrc, true));
    triggers.put("schedule", triggerDoc(schedulerSrc, false));
    triggers.put("webhook", triggerDoc(webhookSrc, false));
    triggers.put("event", triggerDoc(null, false));
    return triggers;
  }

  private Document triggerDoc(Document src, boolean defaultEnabled) {
    boolean enabled = src != null ? src.getBoolean("enable", defaultEnabled) : defaultEnabled;
    return new Document("enabled", enabled).append("conditions", new LinkedList<>());
  }

  /** {@code 4005}'s {@code storage}->{@code workspaces[]} - see the class javadoc for why this lands
   * on revisions rather than the workflow itself. */
  private List<Document> buildWorkspaces(Document storage) {
    List<Document> workspaces = new LinkedList<>();
    if (storage == null) {
      return workspaces;
    }
    addWorkspaceIfEnabled(workspaces, (Document) storage.get("activity"), "workflowrun");
    addWorkspaceIfEnabled(workspaces, (Document) storage.get("workflow"), "workflow");
    return workspaces;
  }

  private void addWorkspaceIfEnabled(List<Document> workspaces, Document storageSpec, String name) {
    if (storageSpec == null || !Boolean.TRUE.equals(storageSpec.getBoolean("enabled", false))) {
      return;
    }
    Document spec = new Document(storageSpec);
    spec.remove("enabled");
    Document workspace = new Document();
    workspace.put("name", name);
    workspace.put("type", name);
    workspace.put("optional", false);
    workspace.put("spec", spec);
    workspaces.add(workspace);
  }

  // =====================================================================================
  // workflow_revisions
  // =====================================================================================

  private Document migrateRevision(
      Document rev,
      String workflowRef,
      int version,
      List<Document> workspaces,
      List<Document> workflowProperties,
      MongoCollection<Document> tasksCollection) {
    Document revision = new Document();
    revision.put("_id", rev.getObjectId("_id"));
    revision.put("workflowRef", workflowRef);
    revision.put("version", version);

    Document dag = (Document) rev.get("dag");
    List<Document> dagTasks = dag != null ? getList(dag, "tasks") : new LinkedList<>();
    revision.put("tasks", migrateTasks(dagTasks, tasksCollection));
    revision.put("workspaces", workspaces);

    Document changelogSrc = (Document) rev.get("changelog");
    if (changelogSrc != null) {
      revision.put(
          "changelog",
          new Document("author", changelogSrc.get("userId"))
              .append("reason", changelogSrc.get("reason"))
              .append("date", changelogSrc.get("date")));
    }

    revision.put("params", mergeWorkflowParams(workflowProperties));
    return revision;
  }

  // =====================================================================================
  // tasks[]
  // =====================================================================================

  private List<Document> migrateTasks(List<Document> dagTasks, MongoCollection<Document> tasksCollection) {
    List<Document> result = new LinkedList<>();
    for (Document dagTask : dagTasks) {
      result.add(migrateTask(dagTask, dagTasks, tasksCollection));
    }
    return result;
  }

  private Document migrateTask(
      Document dagTask, List<Document> allDagTasks, MongoCollection<Document> tasksCollection) {
    String v3Type = dagTask.getString("type");
    boolean isStart = "start".equals(v3Type);
    boolean isEnd = "end".equals(v3Type);
    String resolvedType = resolveTaskType(v3Type);

    Document task = new Document();
    if (isStart) {
      task.put("name", "start");
    } else if (isEnd) {
      task.put("name", "end");
    } else {
      task.put("name", dagTask.getString("label"));
      Object templateId = dagTask.get("templateId");
      if (templateId != null) {
        String taskRef = templateId.toString();
        if (tasksCollection.find(Filters.eq("_id", new ObjectId(taskRef))).first() == null) {
          LOG.warn("Task {} references unknown task id {} — taskRef carried through unresolved", dagTask.get("label"), taskRef);
        }
        task.put("taskRef", taskRef);
      }
      Object templateVersion = dagTask.get("templateVersion");
      if (templateVersion != null) {
        // THE FIX — see the class javadoc's headline-fix section.
        task.put("taskVersion", toInt(templateVersion));
      }
      task.put("results", dagTask.get("results"));
      task.put("params", migrateTaskParams(dagTask.get("properties"), resolvedType));
    }

    task.put("type", resolvedType);
    task.put("dependencies", migrateDependencies(dagTask, allDagTasks));

    Document metadata = (Document) dagTask.get("metadata");
    Document annotations = new Document();
    if (metadata != null && metadata.get("position") != null) {
      annotations.put("boomerang#io/position", metadata.get("position"));
    }
    task.put("labels", new Document());
    task.put("annotations", annotations);
    return task;
  }

  /**
   * {@code 4004}'s nodetype mapping intent, applied to the DAG TASK's own {@code type} field - see
   * the class javadoc for why this differs from v3's {@code task_templates.nodetype} spelling.
   */
  private String resolveTaskType(String v3Type) {
    if (v3Type == null) {
      return null;
    }
    if ("customtask".equalsIgnoreCase(v3Type)) {
      return "custom";
    }
    return v3Type;
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateTaskParams(Object rawProperties, String resolvedType) {
    List<Document> params = new LinkedList<>();
    if (!(rawProperties instanceof List<?> list)) {
      return params;
    }
    boolean isRunWorkflow = "runworkflow".equals(resolvedType) || "runscheduledworkflow".equals(resolvedType);
    for (Object o : list) {
      if (o instanceof Document p) {
        Object name = p.get("key");
        if (isRunWorkflow && name != null && "workflowId".equalsIgnoreCase(name.toString())) {
          // FIX (legacy 4048): rename the run(-scheduled)-workflow task's workflowId param to
          // workflowRef.
          name = "workflowRef";
        }
        Document param = new Document();
        param.put("name", name);
        param.put("value", p.get("value"));
        params.add(param);
      }
    }
    return params;
  }

  @SuppressWarnings("unchecked")
  private List<Document> migrateDependencies(Document dagTask, List<Document> allDagTasks) {
    List<Document> deps = getList(dagTask, "dependencies");
    List<Document> result = new LinkedList<>();
    for (Document dep : deps) {
      Document d = new Document(dep);
      Object switchCondition = d.get("switchCondition");
      d.put("decisionCondition", switchCondition != null ? switchCondition : "");
      Document dependentTask = findByTaskId(allDagTasks, dep.get("taskId"));
      if (dependentTask != null) {
        String depType = dependentTask.getString("type");
        if ("start".equals(depType)) {
          d.put("taskRef", "start");
        } else if ("end".equals(depType)) {
          d.put("taskRef", "end");
        } else {
          d.put("taskRef", dependentTask.getString("label"));
        }
      } else {
        LOG.warn("Dependency references unknown taskId {} — taskRef left unresolved", dep.get("taskId"));
      }
      d.remove("taskId");
      d.remove("switchCondition");
      d.remove("conditionalExecution");
      d.remove("additionalProperties");
      result.add(d);
    }
    return result;
  }

  private Document findByTaskId(List<Document> allDagTasks, Object taskId) {
    if (taskId == null) {
      return null;
    }
    for (Document t : allDagTasks) {
      if (taskId.equals(t.get("taskId"))) {
        return t;
      }
    }
    return null;
  }

  // =====================================================================================
  // workflow-level params (4042/4043-shaped merge - see _0022's mergeParams for the algorithm
  // this reproduces)
  // =====================================================================================

  /**
   * Reproduces {@code 4005}+{@code 4042}'s workflow-{@code properties}->{@code config}+{@code
   * params}->merged-{@code params} pipeline for ONE revision, exactly mirroring {@code
   * _0006__V3MigrateTaskCatalogue#mergeParams}'s algorithm (config-as-base-document, {@code key}->{@code
   * name}, {@code values}->{@code value}, defaultValue/description merged in from the matching
   * naive param by name, unmatched naive params surviving as bare string-typed fallbacks).
   *
   * <p><b>A genuine squashing hazard, fixed here.</b> In the original multi-changeset pipeline,
   * {@code 4005} wrote the SAME naive-params list onto every revision of a workflow, but {@code
   * 4042} ran as a LATER, separate changeset that re-read each revision document fresh from Mongo
   * — so mutating one revision's params/config during its merge never affected another revision's
   * own freshly-deserialized copy. Squashed into one pass with no Mongo round-trip in between,
   * reusing the SAME in-memory naive-params list across multiple revisions of one workflow would
   * let the first revision's merge (which REMOVES matched entries as it goes) silently drain the
   * list for every subsequent revision. This method rebuilds both the config base and the naive
   * params fully from {@code workflowProperties} on every call, so calling it once per revision
   * (as {@link #migrateRevision} does) is safe.
   */
  @SuppressWarnings("unchecked")
  private List<Document> mergeWorkflowParams(List<Document> workflowProperties) {
    List<Document> naiveParams = new LinkedList<>();
    if (workflowProperties != null) {
      for (Document property : workflowProperties) {
        Document param = new Document();
        param.put("name", property.get("key"));
        param.put("type", "string");
        param.put("description", property.get("description"));
        param.put("defaultValue", property.get("defaultValue"));
        naiveParams.add(param);
      }
    }

    List<Document> merged = new LinkedList<>();
    if (workflowProperties != null) {
      for (Document property : workflowProperties) {
        Document param = new Document(property);
        param.put("name", param.get("key"));
        param.remove("key");
        Object values = param.get("values");
        if (values != null && !values.toString().isEmpty()) {
          param.put("value", values);
          param.remove("values");
        }
        Document match = removeByName(naiveParams, param.get("name"));
        if (match != null) {
          Object defaultValue = match.get("defaultValue");
          if (defaultValue != null && !defaultValue.toString().isEmpty()) {
            param.put("defaultValue", defaultValue);
          }
          Object description = match.get("description");
          if (description != null && !description.toString().isEmpty()) {
            param.put("description", description);
          }
        }
        merged.add(param);
      }
    }

    for (Document leftover : naiveParams) {
      Document param = new Document();
      param.put("name", leftover.get("name"));
      param.put("label", leftover.get("name"));
      param.put("type", "string");
      param.put("description", leftover.get("description"));
      param.put("defaultValue", leftover.get("defaultValue"));
      merged.add(param);
    }
    return merged;
  }

  private Document removeByName(List<Document> params, Object name) {
    for (Document param : params) {
      if (param.get("name") != null && param.get("name").toString().equals(String.valueOf(name))) {
        params.remove(param);
        return param;
      }
    }
    return null;
  }

  // =====================================================================================
  // helpers
  // =====================================================================================

  @SuppressWarnings("unchecked")
  private List<Document> getList(Document doc, String key) {
    Object value = doc.get(key);
    return value instanceof List<?> ? (List<Document>) value : new LinkedList<>();
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

  private static String asString(Object value) {
    return value != null ? value.toString() : null;
  }

  @Rollback
  public void rollback() {
    // workflows_revisions is dropped once migrated, and workflows are rewritten in place with no
    // v3 field preserved anywhere else - not restorable, matching the other forward-only v3-only
    // units in this chain.
  }
}
