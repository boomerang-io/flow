package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;

import tools.jackson.databind.ObjectMapper;
import io.boomerang.common.model.WorkflowRunResponsePage;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RetryScope;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.*;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.FilterValuesOutputStream;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.util.PagingUtil;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.dispatcher.PollWakeups;
import io.boomerang.event.entity.EventInboxEntity;
import io.boomerang.event.enums.InboxStatus;
import io.boomerang.engine.RunTimeouts;
import io.boomerang.engine.TaskExecutionService;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.WorkflowExecutionService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.event.repository.EventInboxRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The WorkflowRun domain service - one service for every run operation the product performs.
 *
 * <p>It has two entry shapes over the same operations:
 *
 * <ul>
 *   <li><b>Workspace-scoped</b> ({@code get(workspace, ...)}, {@code cancel(workspace, ...)}, ...)
 *       - the {@code /api/v2/workspace/&#123;workspace&#125;/workflowrun} surface. Each one performs
 *       its own {@link RelationshipService} check before doing the work.
 *   <li><b>Unscoped</b> ({@code get(id, ...)}, {@code cancel(id)}, ...) - internal callers that
 *       carry no workspace and are authorized elsewhere or not at all: the engine's watcher and
 *       execution services, the webhook event path, and the v1 dispatcher callback controller.
 * </ul>
 *
 * <p>F3 collapsed the former {@code api.WorkspaceWorkflowRunService} pass-through into this class:
 * the workspace check and the operation now live together instead of being split across a service
 * boundary that only existed because the engine used to be a separate deployable.
 */
@Service
public class WorkflowRunService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final ActionRepository actionRepository;
  private final TaskRunService taskRunService;
  private final TaskService taskService;
  private final WorkflowExecutionService workflowExecutionService;
  private final TaskExecutionService taskExecutionService;
  private final EventInboxRepository eventInboxRepository;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;
  private final QuotaService quotaService;
  private final ParamLayerService paramLayerService;
  private final PollWakeups pollWakeups;
  private final UserService userService;

  public WorkflowRunService(
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      ActionRepository actionRepository,
      TaskRunService taskRunService,
      TaskService taskService,
      WorkflowExecutionService workflowExecutionService,
      @Lazy TaskExecutionService taskExecutionService,
      EventInboxRepository eventInboxRepository,
      WorkflowRunStateHelper workflowRunStateHelper,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      ObjectMapper objectMapper,
      QuotaService quotaService,
      ParamLayerService paramLayerService,
      PollWakeups pollWakeups,
      UserService userService) {
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.actionRepository = actionRepository;
    this.taskRunService = taskRunService;
    this.taskService = taskService;
    this.workflowExecutionService = workflowExecutionService;
    this.taskExecutionService = taskExecutionService;
    this.eventInboxRepository = eventInboxRepository;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.objectMapper = objectMapper;
    this.quotaService = quotaService;
    this.paramLayerService = paramLayerService;
    this.pollWakeups = pollWakeups;
    this.userService = userService;
  }

  // ── Workspace-scoped operations (the /api/v2 surface) ──────────────────────
  //
  // Every method here performs the SAME relationship check the deleted
  // api.WorkspaceWorkflowRunService performed, then does the work inline.

  /*
   * Get Workflow Run
   *
   * No need to validate params as they are either defaulted or optional
   */
  public ResponseEntity<WorkflowRun> get(String workspace, String workflowRunId, boolean withTasks) {
    requireWorkspaceRelationship(workspace, workflowRunId);
    WorkflowRun wfRun = get(workflowRunId, withTasks);
    filterSensitiveValues(wfRun, new HashMap<>());
    // Last, after the filters above joined the catalogue by id: a consumer reads the slug form.
    slugTaskRefs(wfRun.getTasks());
    return ResponseEntity.ok(wfRun);
  }

  /**
   * Rewrite each task run's {@code taskRef} from the stored task id to the task's slug form, in
   * one batch; a task the catalogue no longer holds keeps its id.
   */
  public void slugTaskRefs(List<TaskRun> tasks) {
    if (tasks == null || tasks.isEmpty()) {
      return;
    }
    Map<String, String> slugs =
        taskService.slugsByRef(
            tasks.stream().map(TaskRun::getTaskRef).filter(Objects::nonNull).toList());
    tasks.forEach(task -> task.setTaskRef(slugs.getOrDefault(task.getTaskRef(), task.getTaskRef())));
  }

  /*
   * Sensitive params are sensitive UPWARD (engine to UI/API consumer), so filtering happens on
   * the workspace-scoped v2 surface only - never on the unscoped reads the engine and dispatcher
   * use, which must see real values. RunParam carries no type on the wire, so the type comes from
   * the definition - and there are TWO definitions that can declare it:
   *
   *   - the workflow revision's param spec, for a workflow-level param referenced as $(params.x);
   *   - the CATALOGUE TASK's own spec, for a value typed straight into a task node's param, which
   *     has no workflow-level param to join against at all.
   *
   * Both are blanked by name, and the UNION of the values they resolve to is then scrubbed
   * run-wide - substitution moves a task-declared secret into a downstream task's param or result
   * under another name, so the scrub cannot be per task. The values of the run workspace's
   * password-typed global and workspace parameters join the union: no definition above declares
   * them, yet substitution writes them into task params, scripts and results. Those are read once
   * per workflow per response (securedByWorkflow). Mutates the response model only.
   */
  void filterSensitiveValues(WorkflowRun wfRun, Map<String, Set<String>> securedByWorkflow) {
    if (wfRun == null) {
      return;
    }
    // Collected first, while the values are still raw: the workflow pass below scrubs, and a
    // value read back after it would be collected as the redaction marker instead of the secret.
    // The secret-typed params come first of all - their values are scrubbed from free text too.
    Set<String> secrets = new HashSet<>(DataAdapterUtil.sensitiveValues(wfRun, ParamType.secret));
    secrets.addAll(filterTaskDeclaredSensitiveValues(wfRun.getTasks()));
    secrets.addAll(securedLayerValues(wfRun.getWorkflowRef(), securedByWorkflow));
    if (wfRun.getWorkflowRevisionRef() != null) {
      workflowRevisionRepository
          .findById(wfRun.getWorkflowRevisionRef())
          .ifPresent(
              revision ->
                  secrets.addAll(
                      DataAdapterUtil.filterWorkflowRunValueByFieldType(
                          wfRun, revision.getParams(), FieldType.PASSWORD.value())));
    }
    DataAdapterUtil.scrubWorkflowRunValues(wfRun, secrets);
    // Last, and authoritative for params: whatever the passes above left, a secret-typed param
    // reads as the redaction marker. The name join stays for runs stored before params carried
    // a type.
    DataAdapterUtil.filterWorkflowRunValueByFieldType(wfRun, ParamType.secret);
  }

  /*
   * The lifecycle responses (start, cancel, pause, resume, retry) are consumer reads too. They
   * carry no tasks, so the type check alone covers them, and it copies rather than mutates.
   */
  private static WorkflowRun redactSecrets(WorkflowRun wfRun) {
    DataAdapterUtil.filterWorkflowRunValueByFieldType(wfRun, ParamType.secret);
    return wfRun;
  }

  /**
   * Blanks every password-typed param each TaskRun's OWN catalogue task declares, and returns the
   * values they resolved to. The join is what the TaskRun already records - {@code taskRef} and
   * {@code taskVersion} - so no field, wire change or migration is involved.
   *
   * <p>The lookup is BATCHED: the distinct (taskRef, taskVersion) pairs across the whole response
   * are resolved in one call to {@link TaskService#getSpecs}, never one query per task. Tasks are
   * attached only by {@code get(id, withTasks=true)}; the paged {@code query} leaves them null, so
   * a list page reaches the early return here and issues no lookup at all.
   *
   * <p>Mutates the given models - callers pass response models or throwaway reads, never
   * something they will persist.
   */
  private Set<String> filterTaskDeclaredSensitiveValues(List<TaskRun> tasks) {
    if (tasks == null || tasks.isEmpty()) {
      return Set.of();
    }
    Set<TaskService.TaskRef> refs =
        tasks.stream()
            .filter(t -> t.getTaskRef() != null && t.getTaskVersion() != null)
            .map(t -> new TaskService.TaskRef(t.getTaskRef(), t.getTaskVersion()))
            .collect(Collectors.toSet());
    if (refs.isEmpty()) {
      return Set.of();
    }
    Map<TaskService.TaskRef, TaskSpec> specs = taskService.getSpecs(refs);
    Set<String> secrets = new HashSet<>();
    tasks.forEach(
        task -> {
          TaskSpec spec =
              specs.get(new TaskService.TaskRef(task.getTaskRef(), task.getTaskVersion()));
          if (spec != null) {
            secrets.addAll(
                DataAdapterUtil.filterTaskRunValueByFieldType(
                    task, spec.getParams(), FieldType.PASSWORD.value()));
          }
        });
    return secrets;
  }

  /*
   * Query for WorkflowRun
   *
   * No need to validate params as they are either defaulted or optional
   */
  public WorkflowRunResponsePage query(
      String queryTeam,
      Optional<Long> fromDate,
      Optional<Long> toDate,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> queryOrder,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTriggers) {

    List<String> wfRefs = workflowsInWorkspace(queryTeam, queryWorkflows);
    if (wfRefs.isEmpty()) {
      Sort sort = Sort.by(new Order(queryOrder.orElse(Direction.ASC), "creationDate"));
      return new WorkflowRunResponsePage(
          List.of(), emptyPage(queryLimit, queryPage, sort), 0);
    }
    // The run-id filter is applied on top of the workspace's workflows, so a caller cannot reach
    // a run outside the workspace by naming its id.
    Page<WorkflowRun> page =
        query(
            fromDate.map(Date::new),
            toDate.map(Date::new),
            queryLimit,
            queryPage,
            queryOrder,
            queryLabels,
            queryStatus,
            queryPhase,
            queryWorkflowRuns,
            Optional.of(wfRefs),
            queryTriggers);
    Map<String, Set<String>> securedByWorkflow = new HashMap<>();
    page.getContent().forEach(run -> filterSensitiveValues(run, securedByWorkflow));
    return new WorkflowRunResponsePage(
        page.getContent(), page.getPageable(), page.getTotalElements());
  }

  /**
   * Query the task runs of the workspace's runs, across runs: by label (a run's labels are on
   * each of its task runs, and a child run has its parent's) or by run, then by workflow, task
   * name, status, phase and creation date. A task name also matches its for-each items, and
   * {@code foreach} keeps for-each parents only ({@code true}) or leaves them out ({@code false}).
   * Each row leaves out what can carry a secret value; {@code get} returns the whole task run.
   */
  public TaskRunResponsePage queryTaskRuns(
      String workspace,
      Optional<Long> fromDate,
      Optional<Long> toDate,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> queryOrder,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTasks,
      Optional<Boolean> queryForeach) {
    // Task runs are indexed by run and by label, not by workflow, so a query needs one of the two.
    if (queryLabels.filter(labels -> !labels.isEmpty()).isEmpty()
        && queryWorkflowRuns.filter(runs -> !runs.isEmpty()).isEmpty()) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "labels,workflowruns");
    }
    Direction direction = queryOrder.orElse(Direction.ASC);
    // Task runs of one run share a creation time, so the id breaks the tie and pages stay stable.
    Sort sort = Sort.by(new Order(direction, "creationDate"), new Order(direction, "_id"));
    List<String> wfRefs = workflowsInWorkspace(workspace, queryWorkflows);
    if (wfRefs.isEmpty()) {
      return new TaskRunResponsePage(List.of(), emptyPage(queryLimit, queryPage, sort), 0);
    }
    QueryCriteria criteria =
        new QueryCriteria()
            .between("creationDate", fromDate.map(Date::new), toDate.map(Date::new))
            .labels(queryLabels)
            .in("status", RunStatus.class, queryStatus, "status")
            .in("phase", RunPhase.class, queryPhase, "phase")
            .in("workflowRunRef", queryWorkflowRuns)
            .in("workflowRef", wfRefs);
    queryTasks.ifPresent(tasks -> criteria.add(taskNames(tasks)));
    // A for-each parent is the task run carrying the foreach setting.
    queryForeach.ifPresent(parents -> criteria.add(Criteria.where("foreach").exists(parents)));
    Query query = new Query(criteria.build());
    query
        .fields()
        .include("type", "name", "labels", "creationDate", "startTime", "duration", "status")
        .include("phase", "statusReason", "taskRef", "workflowRef", "workflowRunRef", "parentRef")
        .include("index", "reusedFromRef", "claim", "retry", "foreach");
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);
    List<TaskRun> rows =
        mongoTemplate.find(query, TaskRunEntity.class).stream()
            .map(WorkflowRunService::listed)
            .collect(Collectors.toList());
    slugTaskRefs(rows);
    Page<TaskRun> page =
        PagingUtil.page(rows, pageable, mongoTemplate, query, TaskRunEntity.class);
    return new TaskRunResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());
  }

  /**
   * The ids of the workspace's workflows, narrowed by {@code workflows} when given. A workspace
   * the caller cannot reach (or that does not exist) is a 404 like every other unresolvable
   * workspace reference; a reachable one with nothing to list - new, emptied, or a filter that
   * matches none - answers no ids, so its query is an empty page, as insight and count answer.
   */
  private List<String> workflowsInWorkspace(String workspace, Optional<List<String>> workflows) {
    List<String> wfRefs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, workspace, workflows);
    if (wfRefs.isEmpty()
        && !relationshipService.check(
            RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF);
    }
    return wfRefs;
  }

  /** The page an empty answer reports: the requested shape, so a caller pages on unchanged. */
  private static Pageable emptyPage(
      Optional<Integer> queryLimit, Optional<Integer> queryPage, Sort sort) {
    return (queryLimit.isPresent())
        ? PageRequest.of(queryPage.orElse(0), queryLimit.get(), sort)
        : Pageable.unpaged();
  }

  // A task name matches its node and the node's for-each items, stored as name[0], name[1], ...
  private static Criteria taskNames(List<String> tasks) {
    String names = tasks.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    return Criteria.where("name").regex("^(?:" + names + ")(?:\\[[0-9]+\\])?$");
  }

  // A listed task run leaves out its params, results, spec and annotations, as the projection
  // leaves out its message: the parts that can carry a secret value, so a row needs no scrub.
  private static TaskRun listed(TaskRunEntity entity) {
    TaskRun taskRun = new TaskRun(entity);
    taskRun.setParams(null);
    taskRun.setResults(null);
    taskRun.setSpec(null);
    taskRun.setAnnotations(null);
    return taskRun;
  }

  /*
   * Retrieve the counts by status for a specific period of time and filters
   */
  public WorkflowRunCount count(
      String queryTeam,
      Optional<Long> from,
      Optional<Long> to,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows) {
    List<String> wfRefs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, queryTeam, queryWorkflows);
    return count(from.map(Date::new), to.map(Date::new), queryLabels, Optional.of(wfRefs));
  }

  /*
   * Start WorkflowRun
   *
   * TODO: do we expose this one?
   */
  public ResponseEntity<WorkflowRun> start(
      String workspace, String workflowRunId, Optional<WorkflowRunRequest> optRunRequest) {
    requireWorkspaceRelationship(workspace, workflowRunId);
    return ResponseEntity.ok(redactSecrets(start(workflowRunId, optRunRequest)));
  }

  /*
   * Cancel WorkflowRun
   */
  public ResponseEntity<WorkflowRun> cancel(String workspace, String workflowRunId) {
    requireWorkspaceRelationship(workspace, workflowRunId);
    WorkflowRun wfRun = cancel(workflowRunId);
    // Close any Action (approval / manual task) the cancelled run left open. Inlined from the
    // deleted WorkspaceActionService.cancelAllByWorkflowRun, which was a one-line delegation to
    // this repository call and had no other caller.
    actionRepository.updateStatusByWorkflowRunRef(workflowRunId, ActionStatus.cancelled);
    return ResponseEntity.ok(redactSecrets(wfRun));
  }

  /*
   * Pause WorkflowRun
   */
  public ResponseEntity<WorkflowRun> pause(String workspace, String workflowRunId) {
    requireWorkspaceRelationship(workspace, workflowRunId);
    return ResponseEntity.ok(redactSecrets(pause(workflowRunId)));
  }

  /*
   * Resume WorkflowRun
   */
  public ResponseEntity<WorkflowRun> resume(String workspace, String workflowRunId) {
    requireWorkspaceRelationship(workspace, workflowRunId);
    return ResponseEntity.ok(redactSecrets(resume(workflowRunId)));
  }

  /*
   * Retry WorkflowRun
   */
  public ResponseEntity<WorkflowRun> retry(String workspace, String workflowRunId) {
    return retry(workspace, workflowRunId, Optional.empty());
  }

  /*
   * Retry WorkflowRun with a scope: an absent body is a fresh run of the whole graph.
   */
  public ResponseEntity<WorkflowRun> retry(
      String workspace, String workflowRunId, Optional<WorkflowRunRetryRequest> retryRequest) {
    requireWorkspaceRelationship(workspace, workflowRunId);

    // Refuse-first: an unresolvable owner fails the request BEFORE the clone is created, keeping
    // this operation all-or-nothing for a caller. The unscoped retry below records the ownership
    // edge itself, against this same resolution - never the `team` path segment, which only
    // proves the caller can reach this run through that path (and for a global-scope token
    // RelationshipService.check returns true for ANY path workspace), and once recorded wrong
    // owners permanently.
    owningWorkspace(workflowRunId);

    return ResponseEntity.ok(
        redactSecrets(
            retry(workflowRunId, false, 1, retryRequest.orElseGet(WorkflowRunRetryRequest::new))));
  }

  /**
   * A TaskRun's log, for the {@code /api/v2/taskrun/&#123;taskRunId&#125;/log} surface.
   *
   * <p>F3 collapsed the former {@code api.WorkspaceTaskRunService} pass-through - a single method
   * that resolved the TaskRun through {@code engine.TaskRunService}, checked the caller against its
   * owning WorkflowRun, then scrubbed the stream - into this class rather than into {@link
   * io.boomerang.engine.TaskRunService}: the guard's anchor is the owning WorkflowRun, the scrub's
   * type authority is that run's revision, and both are already this service's job (see {@link
   * #requireWorkspaceRelationship} and {@link #filterSensitiveValues}). {@code
   * engine.TaskRunService.streamLog} stays exactly as it is - the raw, unscrubbed stream the
   * engine owns - and is called from here.
   *
   * <p>The check is deliberately NOT {@link #requireWorkspaceRelationship}: there is no TASKRUN
   * relationship node, and this route carries no workspace path segment, so ownership is reachable
   * only through the parent WorkflowRun and no intermediate containment can be applied. That is
   * the check the pass-through performed, carried over unchanged.
   */
  public StreamingResponseBody streamTaskRunLog(String taskRunId) {
    if (Objects.isNull(taskRunId) || taskRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REQ);
    }
    TaskRun taskRun = taskRunService.get(taskRunId).getBody();
    if (!relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        taskRun.getWorkflowRunRef(),
        Optional.empty(),
        Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
    LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
    // A task run a scoped retry carried over never ran: its log is the origin task run's.
    String loggedTaskRunId =
        (taskRun.getReusedFromRef() != null) ? taskRun.getReusedFromRef() : taskRunId;
    StreamingResponseBody body = taskRunService.streamLog(loggedTaskRunId);
    // Sensitive-upward, the same rule filterSensitiveValues applies to the run payloads: a script
    // that echoes a password-typed param shows it in its log, so the stream is scrubbed at this
    // workspace-scoped surface. The dispatcher/engine never read logs through here. A carried-over
    // log is scrubbed with the origin run's values as well, since the override may have changed
    // them.
    Set<String> secrets = taskRunSensitiveValues(taskRun.getWorkflowRunRef());
    if (taskRun.getReusedFromRef() != null) {
      taskRunRepository
          .findById(taskRun.getReusedFromRef())
          .ifPresent(origin -> secrets.addAll(taskRunSensitiveValues(origin.getWorkflowRunRef())));
    }
    if (secrets.isEmpty()) {
      return body;
    }
    return outputStream -> {
      FilterValuesOutputStream scrubbed = new FilterValuesOutputStream(outputStream, secrets);
      body.writeTo(scrubbed);
      scrubbed.finish();
    };
  }

  // The resolved values of every password-typed param on the owning run, per DataAdapterUtil's
  // name-join against BOTH type authorities filterSensitiveValues uses: the workflow revision's
  // param spec, and each task's own catalogue spec. Every task of the run is included, not just
  // the one being streamed - substitution can carry a sibling task's declared secret into this
  // task's script, and a script that echoes either shows it in this log. Manual action
  // instructions are scrubbed with the same values (ActionService).
  Set<String> taskRunSensitiveValues(String workflowRunRef) {
    Set<String> secrets = new HashSet<>();
    workflowRunRepository
        .findById(workflowRunRef)
        .ifPresent(
            run -> {
              // The run's own secret-typed params, which need no definition to be recognised.
              secrets.addAll(DataAdapterUtil.sensitiveValues(run.getParams(), ParamType.secret));
              secrets.addAll(securedLayerValues(run.getWorkflowRef(), new HashMap<>()));
              Optional.ofNullable(run.getWorkflowRevisionRef())
                  .flatMap(workflowRevisionRepository::findById)
                  .ifPresent(
                      revision ->
                          secrets.addAll(
                              DataAdapterUtil.sensitiveValues(
                                  revision.getParams(),
                                  run.getParams(),
                                  FieldType.PASSWORD.value())));
            });
    // Throwaway models, read only to collect the values - the blanking they undergo is discarded.
    List<TaskRun> taskRuns = getTaskRuns(workflowRunRef);
    taskRuns.forEach(
        task -> secrets.addAll(DataAdapterUtil.sensitiveValues(task.getParams(), ParamType.secret)));
    secrets.addAll(filterTaskDeclaredSensitiveValues(taskRuns));
    return secrets;
  }

  /*
   * The values of the password-typed global and workspace parameters a run's values were resolved
   * from - its workspace is the one that owns its workflow, as the engine resolved. Cached per
   * workflow for the response being filtered.
   */
  private Set<String> securedLayerValues(
      String workflowRef, Map<String, Set<String>> securedByWorkflow) {
    return securedByWorkflow.computeIfAbsent(
        String.valueOf(workflowRef), ref -> paramLayerService.securedValues(workflowRef));
  }

  /**
   * The workspace that owns a WorkflowRun: its own recorded owner, or - for a run created without
   * an ownership edge, which is what the engine's auto-retry produces - the owner of the Workflow
   * it ran. The fallback is the same resolution {@code RelationshipEventListener
   * .onChildWorkflowRunCreated} and {@code ScheduleWatcher.resolveWorkspace} already use, and it keeps
   * a chained retry from failing on a missing parent.
   *
   * <p>When neither is recorded there is no third option: the {@code team} path segment is not an
   * owner, because a global-scope token passes {@link RelationshipService#check} for any workspace
   * (RelationshipService:417-419) - which is precisely how such a caller reaches a graph-orphaned
   * run - and adopting it is the wrong-owner bug this resolution exists to fix. So this refuses
   * with the mapped {@link BoomerangError#WORKSPACE_INVALID_REF} (404) the rest of the product already
   * uses for an unresolvable workspace, rather than minting a run no workspace owns.
   */
  private String owningWorkspace(String workflowRunId) {
    String workspace = owningWorkspaceOrNull(workflowRunId);
    if (workspace == null) {
      throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF);
    }
    return workspace;
  }

  /**
   * The workspace's name for the ref an ownership edge records. The quota lookups are by name, so
   * the ref the graph stores has to be resolved before either can answer for this workspace.
   */
  private String workspaceNameOrNull(String workspaceRef) {
    if (workspaceRef == null || workspaceRef.isBlank()) {
      return null;
    }
    try {
      return relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, workspaceRef);
    } catch (RuntimeException e) {
      LOGGER.warn("Owning workspace {} has no relationship node.", workspaceRef);
      return null;
    }
  }

  /**
   * The same resolution, answering {@code null} instead of refusing when no workspace owns the
   * run or its Workflow - for the engine's auto-retry, which must not fail a run's recovery over
   * graph bookkeeping. A missing run still throws: there is nothing to retry at all.
   */
  private String owningWorkspaceOrNull(String workflowRunId) {
    String workspace =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, workflowRunId);
    if (workspace != null && !workspace.isBlank()) {
      return workspace;
    }
    String workflowRef =
        workflowRunRepository
            .findById(workflowRunId)
            .map(WorkflowRunEntity::getWorkflowRef)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    workspace =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef);
    if (workspace == null || workspace.isBlank()) {
      LOGGER.error(
          "[{}] Neither the WorkflowRun nor its Workflow ({}) has an owning workspace in the"
              + " relationship graph.",
          workflowRunId,
          workflowRef);
      return null;
    }
    return workspace;
  }

  /**
   * The single workspace guard for the operations above: the caller must be able to reach this
   * WorkflowRun through the workspace named in the path. A blank id fails the same way an
   * unreachable one does, exactly as the pass-through did.
   */
  private void requireWorkspaceRelationship(String workspace, String workflowRunId) {
    if (workflowRunId == null
        || workflowRunId.isBlank()
        || !relationshipService.check(
            RelationshipType.WORKFLOWRUN,
            workflowRunId,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)))) {
      // TODO: do we want to return invalid ref or unauthorized
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  // ── Unscoped operations (engine, webhook and v1 dispatcher callers) ────────

  public WorkflowRun get(String wfRunId, boolean withTasks) {
    if (wfRunId == null || wfRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    Optional<WorkflowRunEntity> wfRunEntity = workflowRunRepository.findById(wfRunId);
    if (wfRunEntity.isPresent()) {
      WorkflowRun wfRun = ConvertUtil.entityToModel(wfRunEntity.get(), WorkflowRun.class);
      updateWorkflowDetails(wfRunEntity.get(), wfRun);
      resolveInitiatingRun(wfRunEntity.get(), wfRun);
      nameInitiators(List.of(wfRun));
      if (withTasks) {
        wfRun.setTasks(getTaskRuns(wfRunId));
      }
      return wfRun;
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  public Page<WorkflowRun> query(
      Optional<Date> from,
      Optional<Date> to,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTriggers) {
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    Query query =
        new Query(
            new QueryCriteria()
                .between("creationDate", from, to)
                .labels(queryLabels)
                .in("status", RunStatus.class, queryStatus, "status")
                .in("phase", RunPhase.class, queryPhase, "phase")
                .in("id", queryWorkflowRuns)
                .in("workflowRef", queryWorkflows)
                .in("trigger", queryTriggers)
                .build());
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);

    List<WorkflowRunEntity> wfRunEntities = mongoTemplate.find(query, WorkflowRunEntity.class);

    // Convert to WorkflowRun and add Workflow Name
    List<WorkflowRun> wfRuns = new LinkedList<>();
    wfRunEntities.forEach(
        e -> {
          WorkflowRun wfRun = ConvertUtil.entityToModel(e, WorkflowRun.class);
          updateWorkflowDetails(e, wfRun);
          wfRuns.add(wfRun);
        });
    nameInitiators(wfRuns);

    Page<WorkflowRun> pages =
        PagingUtil.page(wfRuns, pageable, mongoTemplate, query, WorkflowRunEntity.class);

    return pages;
  }

  /*
   * Generates stats for a given set of filters
   */
  public WorkflowRunCount count(
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> labels,
      Optional<List<String>> queryWorkflows) {
    Query query =
        new Query(
            new QueryCriteria()
                .between("creationDate", from, to)
                .labels(labels)
                .in("workflowRef", queryWorkflows)
                .build());
    LOGGER.debug("Query: " + query.toString());
    List<WorkflowRunEntity> wfRunEntities = mongoTemplate.find(query, WorkflowRunEntity.class);

    // Collate by Status run count
    Map<String, Long> result =
        wfRunEntities.stream()
            .collect(groupingBy(v -> getStatusValue(v), Collectors.counting())); // NOSONAR
    result.put("all", Long.valueOf(wfRunEntities.size()));

    Arrays.stream(RunStatus.values()).forEach(v -> result.putIfAbsent(v.getStatus(), 0L));

    WorkflowRunCount wfRunCount = new WorkflowRunCount();
    wfRunCount.setStatus(result);
    return wfRunCount;
  }

  private String getStatusValue(WorkflowRunEntity v) {
    return v.getStatus() == null ? "no_status" : v.getStatus().getStatus();
  }

  /*
   * Queues the Workflow to be executed (and optionally starts the execution)
   *
   * A run with a workspace is never started directly here: the dispatcher must provision it
   * first, so the run stays ready/pending for its provisioning claim (findClaimableForProvision)
   * and the dispatcher starts it itself once provisioned (PUT .../workflowrun/{id}/start). Only a
   * run with no workspace - nothing to provision - starts on this call.
   *
   * A run waiting for its provisioning claim has no start to bake its deadline from, so it takes
   * one from its creation: the timeout sweep bounds the wait for a dispatcher as it bounds the
   * run, and the start re-stamps the deadline from the start time.
   */
  public WorkflowRun run(WorkflowRunEntity wfRunEntity, boolean start) {
    boolean hasWorkspace = wfRunEntity.getWorkspace() != null;
    if (start && hasWorkspace) {
      wfRunEntity.setTimeoutAt(
          RunTimeouts.deadline(wfRunEntity.getCreationDate(), wfRunEntity.getTimeout()));
    }
    workflowRunRepository.save(wfRunEntity);
    workflowExecutionService.queue(wfRunEntity.getId());

    // Only an admitted run goes on: one parked for a slot of its workflow, skipped over the
    // workflow's limit, or found invalid is returned as it stands, and the response carries that
    // status truthfully.
    WorkflowRunEntity queued = workflowRunRepository.findById(wfRunEntity.getId()).get();
    boolean admitted = RunStatus.ready.equals(queued.getStatus());
    if (start && hasWorkspace && admitted) {
      LOGGER.info(
          "[{}] Run has a workspace - leaving ready/pending for the dispatcher to provision and start.",
          wfRunEntity.getId());
      // The run is claimable for provisioning: every workflow poll held on this instance looks
      // again now.
      pollWakeups.runProvisionable();
    }
    if (start && !hasWorkspace && admitted) {
      return this.start(wfRunEntity.getId(), Optional.empty());
    }
    return ConvertUtil.entityToModel(queued, WorkflowRun.class);
  }

  public WorkflowRun start(String workflowRunId, Optional<WorkflowRunRequest> optRunRequest) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isPresent()) {
      WorkflowRunEntity wfRunEntity = optWfRunEntity.get();
      // Add values from Run Request
      if (optRunRequest.isPresent()) {
        logPayload(optRunRequest.get());
        // The boomerang.io/ label and annotation prefixes are reserved for the server: a start
        // never sets one.
        wfRunEntity
            .getLabels()
            .putAll(ServerLabel.withoutServerLabels(optRunRequest.get().getLabels()));
        optRunRequest.get().getAnnotations().entrySet().stream()
            .filter(e -> !e.getKey().startsWith(ParamLayerService.SERVER_ANNOTATION_PREFIX))
            .forEach(e -> wfRunEntity.getAnnotations().put(e.getKey(), e.getValue()));
        wfRunEntity.setParams(
            ParameterUtil.addUniqueParams(
                wfRunEntity.getParams(),
                ParameterUtil.suppliedParams(
                    wfRunEntity.getParams(), optRunRequest.get().getParams())));
        // The rule submit holds a merged request to: a secret is a string only.
        WorkflowService.rejectNonStringSecrets(wfRunEntity.getParams());
        // A request carrying a workspace overrides the run's; the dispatcher's start sends none.
        if (optRunRequest.get().getWorkspace() != null) {
          optRunRequest.get().getWorkspace().settle();
          wfRunEntity.setWorkspace(optRunRequest.get().getWorkspace());
        }
        workflowRunRepository.save(wfRunEntity);
      }
      workflowExecutionService.start(workflowRunId);

      // Retrieve the refreshed status
      WorkflowRunEntity updatedWfRunEntity = workflowRunRepository.findById(workflowRunId).get();
      return ConvertUtil.entityToModel(updatedWfRunEntity, WorkflowRun.class);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  public WorkflowRun cancel(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isPresent()) {
      workflowExecutionService.cancel(workflowRunId);
      // Retrieve the refreshed status
      return ConvertUtil.entityToModel(
          workflowRunRepository.findById(workflowRunId).get(), WorkflowRun.class);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  public WorkflowRun pause(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    // Pause Compare-And-Set: only a running, not-yet-paused run gains the flag. Claiming,
    // admission and the recovery sweeps exclude it from here on.
    if (!workflowRunStateHelper.tryPause(workflowRunId)) {
      LOGGER.info("[{}] WorkflowRun not running or already paused. Nothing to pause.", workflowRunId);
    }
    return ConvertUtil.entityToModel(
        workflowRunRepository
            .findById(workflowRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF)),
        WorkflowRun.class);
  }

  public WorkflowRun resume(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    // Resume = clear the flag + reconcile: the advance resumes whatever the pause held back.
    if (workflowRunStateHelper.tryResume(workflowRunId)) {
      taskExecutionService.advance(workflowRunId);
    } else {
      LOGGER.info("[{}] WorkflowRun not paused. Nothing to resume.", workflowRunId);
    }
    return ConvertUtil.entityToModel(
        workflowRunRepository
            .findById(workflowRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF)),
        WorkflowRun.class);
  }

  /*
   * Times out a WorkflowRun. Engine-internal: called only by the WorkflowWatcher timeout sweep
   * and TaskExecutionService's final-task-timeout path, never from an API surface.
   */
  public WorkflowRun timeout(String workflowRunId, boolean taskRunTimeout) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    // The run reads timed out at once; timeoutWorkflow, off this thread, completes it, ends its
    // tasks and starts any automatic retry. Only the winner of the first Compare-And-Set goes on.
    WorkflowRunEntity preImage = workflowRunStateHelper.tryMarkTimedOut(workflowRunId);
    if (preImage != null) {
      // The cause is known here; the completion path just writes the message it is given.
      String statusMessage =
          taskRunTimeout
              ? "A TaskRun exceeded it's timeout."
              : MessageFormatter.format(
                      "The WorkflowRun exceeded the timeout. Timeout was set to {} minutes",
                      preImage.getTimeout())
                  .getMessage();
      workflowExecutionService.timeout(workflowRunId, statusMessage);
    } else {
      LOGGER.info("[{}] WorkflowRun not running. Nothing to timeout.", workflowRunId);
    }
    return ConvertUtil.entityToModel(
        workflowRunRepository
            .findById(workflowRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF)),
        WorkflowRun.class);
  }

  public WorkflowRun retry(String workflowRunId, boolean start, long retryCount) {
    return retry(workflowRunId, start, retryCount, new WorkflowRunRetryRequest());
  }

  /**
   * Retry as a new run. With a scope other than {@code all}, every task run of the origin that
   * succeeded or was skipped and is not downstream of the retry point is carried over as a copy
   * under the new run - results included, claim and deadline dropped - so the plan adopts it by
   * name and only the rest executes. The origin is cancelled when it is still active, so one
   * lineage never has two live runs.
   */
  public WorkflowRun retry(
      String workflowRunId, boolean start, long retryCount, WorkflowRunRetryRequest request) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }
    // Ownership travels with creation: user retries and the engine's auto-retry both come through
    // here, so both record the same owner through this one path. Resolved BEFORE the clone is
    // created, so resolution can never fail a run that already exists and is queued. Deliberate
    // asymmetry with retry(team, id): the scoped caller is refused outright on an unresolvable
    // owner (owningWorkspace throws before delegating here), but the engine's auto-retry must not
    // fail a run's recovery over graph bookkeeping - it logs and retries ownerless, preserving
    // the engine path's previous behaviour for the orphan case.
    String owner = owningWorkspaceOrNull(workflowRunId);
    if (owner == null) {
      LOGGER.warn("[{}] Retrying without an owning workspace.", workflowRunId);
    }
    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isPresent()) {
      WorkflowRunEntity wfRunEntity = optWfRunEntity.get();
      // Resolved against the origin before it is turned into the clone below, and before anything
      // is written: a refused scope leaves the origin untouched.
      RetryPlan plan = retryPlan(wfRunEntity, request);
      // The clone is a new run, so it clears the same quotas a submit clears, and it is clamped
      // to the ceiling as it stands now - which may have been lowered since the original submit.
      // The floor cannot have moved: a retry re-runs the same revision with the same request.
      // Checked before anything is written, so a refusal leaves the source run untouched. A run
      // no workspace owns has no quotas to check and no ceiling to clamp to.
      String ownerName = workspaceNameOrNull(owner);
      if (ownerName != null) {
        quotaService.assertRunQuotas(ownerName, Optional.ofNullable(wfRunEntity.getWorkspace()));
        long ceiling = quotaService.maxRunDuration(ownerName);
        if (ceiling > 0
            && (wfRunEntity.getTimeout() == null
                || wfRunEntity.getTimeout() == 0
                || wfRunEntity.getTimeout() > ceiling)) {
          wfRunEntity.setTimeout(ceiling);
        }
      }
      wfRunEntity.setCreationDate(new Date());
      wfRunEntity.setStatus(RunStatus.notstarted);
      wfRunEntity.setPhase(RunPhase.pending);
      wfRunEntity.setId(null);
      wfRunEntity.setStatusMessage(null);
      wfRunEntity.setDuration(0);
      wfRunEntity.setStartTime(null);
      wfRunEntity.setRetryCount(retryCount);
      // The clone is a fresh execution: it must not inherit the source run's claim ownership,
      // baked deadline, approval gate, status override or produced results.
      wfRunEntity.setClaim(null);
      wfRunEntity.setTimeoutAt(null);
      wfRunEntity.setAwaitingApproval(false);
      wfRunEntity.setStatusOverride(null);
      // A scoped retry keeps the run results its carried-over tasks produced; a fresh run has none.
      wfRunEntity.setResults((plan != null) ? plan.carriedResults() : new LinkedList<>());
      wfRunEntity.setRetryFrom((plan != null) ? plan.retryFrom() : null);
      // Overrides merge over the origin's own: a param replaces the one of that name, a label is
      // put over the one of that key.
      ParameterUtil.addUniqueParams(
          wfRunEntity.getParams(),
          ParameterUtil.suppliedParams(wfRunEntity.getParams(), request.getParams()));
      WorkflowService.rejectNonStringSecrets(wfRunEntity.getParams());
      wfRunEntity.getLabels().putAll(ServerLabel.withoutServerLabels(request.getLabels()));
      // Lineage on typed fields: initiatedByRef points at the first origin (preserved across
      // chained retries), trigger marks this run as a retry.
      if (!TriggerEnum.retry.getTrigger().equals(wfRunEntity.getTrigger())) {
        wfRunEntity.setInitiatedByRef(workflowRunId);
        wfRunEntity.setTrigger(TriggerEnum.retry.getTrigger());
      }
      workflowRunRepository.save(wfRunEntity);
      // A retry that cannot be put in place leaves nothing behind: the clone, its copies and its
      // node are discarded, the origin is untouched, and the caller gets a coded error.
      try {
        if (plan != null) {
          carryOver(plan.carried(), wfRunEntity.getId());
        }

        // Recorded before the clone is queued, so it never executes as reachable-but-unowned
        // (visible in /query via its Workflow yet denied on GET /{id}).
        if (owner != null) {
          relationshipService.createNodeAndEdge(
              RelationshipType.WORKSPACE,
              owner,
              RelationshipLabel.HAS_WORKFLOWRUN,
              RelationshipType.WORKFLOWRUN,
              wfRunEntity.getId(),
              wfRunEntity.getId(),
              Optional.empty(),
              Optional.empty());
        }

        workflowExecutionService.queue(wfRunEntity.getId());
      } catch (RuntimeException e) {
        LOGGER.error("[{}] Retry of {} could not be put in place.", wfRunEntity.getId(), workflowRunId, e);
        discard(wfRunEntity.getId());
        throw (e instanceof BoomerangException boomerang)
            ? boomerang
            : new BoomerangException(
                e,
                1000,
                "WORKFLOW_RUNTIME_EXCEPTION",
                "[{0}] Failed to retry the run: the new run could not be put in place.",
                HttpStatus.INTERNAL_SERVER_ERROR,
                workflowRunId);
      }

      // A scoped retry taken while the origin still runs - the usual case when a run sits at a
      // review gate and a stage is redone - cancels the origin once the new run is in place. A
      // plain retry leaves the origin alone, as it always has.
      if (plan != null && plan.originActive()) {
        workflowExecutionService.cancel(workflowRunId);
      }

      if (start) {
        return this.start(wfRunEntity.getId(), Optional.empty());
      } else {
        // Retrieve the refreshed status
        return ConvertUtil.entityToModel(
            workflowRunRepository.findById(wfRunEntity.getId()).get(), WorkflowRun.class);
      }
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /**
   * What a scoped retry carries over and what it re-runs, resolved from the origin's task runs and
   * its revision's graph. {@code null} for the whole-graph retry.
   */
  private record RetryPlan(
      RetryFrom retryFrom,
      List<TaskRunEntity> carried,
      List<RunResult> carriedResults,
      boolean originActive) {}

  private RetryPlan retryPlan(WorkflowRunEntity origin, WorkflowRunRetryRequest request) {
    RetryScope scope = (request.getScope() != null) ? request.getScope() : RetryScope.all;
    List<String> nodes = (request.getNodes() != null) ? request.getNodes() : List.of();
    if (scope != RetryScope.nodes && !nodes.isEmpty()) {
      throw retryInvalid("Task names are only read when the scope is nodes.");
    }
    if (scope == RetryScope.all) {
      return null;
    }
    if (scope == RetryScope.nodes && nodes.isEmpty()) {
      throw retryInvalid("The scope nodes needs at least one task name.");
    }
    WorkflowRevisionEntity revision =
        Optional.ofNullable(origin.getWorkflowRevisionRef())
            .flatMap(workflowRevisionRepository::findById)
            .orElseThrow(() -> retryInvalid("The run's workflow revision no longer exists."));
    Map<String, WorkflowTask> tasksByName = new HashMap<>();
    Map<String, Set<String>> upstream = new HashMap<>();
    Map<String, Set<String>> downstream = new HashMap<>();
    for (WorkflowTask task : revision.getTasks()) {
      tasksByName.put(task.getName(), task);
      upstream.computeIfAbsent(task.getName(), k -> new HashSet<>());
      downstream.computeIfAbsent(task.getName(), k -> new HashSet<>());
      for (WorkflowTaskDependency dependency : task.getDependencies()) {
        upstream.get(task.getName()).add(dependency.getTaskRef());
        downstream.computeIfAbsent(dependency.getTaskRef(), k -> new HashSet<>()).add(task.getName());
      }
    }
    Map<String, TaskRunEntity> runsByName =
        taskRunRepository.findByWorkflowRunRefAndParentRefIsNull(origin.getId()).stream()
            .collect(Collectors.toMap(TaskRunEntity::getName, t -> t, (a, b) -> a));

    // Everything that neither succeeded nor was skipped re-runs with its descendants in every
    // scope; named tasks are added to that. A skipped task is not carried over either: it is
    // planned afresh, so one the run never reached runs, and one a condition skipped decides again.
    Set<String> seed = new HashSet<>();
    for (WorkflowTask task : revision.getTasks()) {
      TaskRunEntity taskRun = runsByName.get(task.getName());
      if (!isCarriable(taskRun)
          && !(taskRun != null && RunStatus.skipped.equals(taskRun.getStatus()))) {
        seed.add(task.getName());
      }
    }
    // A succeeded run's skipped tasks were all decided by conditions, so there is nothing to redo.
    if (scope == RetryScope.failed
        && seed.isEmpty()
        && RunStatus.succeeded.equals(origin.getStatus())) {
      throw retryInvalid("No task of this run failed.");
    }
    for (String name : nodes) {
      WorkflowTask task = tasksByName.get(name);
      if (task == null) {
        throw retryInvalid("Task " + name + " is not in this run's workflow.");
      }
      if (TaskType.start.equals(task.getType()) || TaskType.end.equals(task.getType())) {
        throw retryInvalid("Task " + name + " is the start or end of the workflow.");
      }
      refuseInsideLock(name, tasksByName, closure(Set.of(name), upstream), closure(Set.of(name), downstream));
      seed.add(name);
    }
    Set<String> rerun = closure(seed, downstream);

    List<TaskRunEntity> carried = new LinkedList<>();
    for (WorkflowTask task : revision.getTasks()) {
      TaskRunEntity taskRun = runsByName.get(task.getName());
      if (!rerun.contains(task.getName()) && isCarriable(taskRun)) {
        carried.add(taskRun);
      }
    }
    // A run result written by a set-property task that re-runs is written again; the rest stay.
    Set<String> rewritten = new HashSet<>();
    for (String name : rerun) {
      WorkflowTask task = tasksByName.get(name);
      if (TaskType.setwfproperty.equals(task.getType())
          && ParameterUtil.containsName(task.getParams(), "output")) {
        rewritten.add(String.valueOf(ParameterUtil.getValue(task.getParams(), "output")));
      }
    }
    List<RunResult> carriedResults =
        origin.getResults().stream().filter(r -> !rewritten.contains(r.getName())).toList();

    RetryFrom retryFrom = new RetryFrom();
    retryFrom.setScope(scope);
    retryFrom.setNodes(new LinkedList<>(nodes));
    return new RetryPlan(
        retryFrom,
        carried,
        new LinkedList<>(carriedResults),
        !RunPhase.completed.equals(origin.getPhase()));
  }

  // A task run is carried over only when it succeeded; skipped, absent, failed, cancelled, timed
  // out, or still in flight are all planned afresh.
  private static boolean isCarriable(TaskRunEntity taskRun) {
    return taskRun != null && RunStatus.succeeded.equals(taskRun.getStatus());
  }

  // What a retry wrote before it failed: the clone, its task-run copies and its node.
  private void discard(String wfRunId) {
    taskRunRepository.deleteByWorkflowRunRef(wfRunId);
    relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKFLOWRUN, wfRunId);
    workflowRunRepository.deleteById(wfRunId);
  }

  // Every task reachable from the seed over the given edges, the seed included.
  private static Set<String> closure(Set<String> seed, Map<String, Set<String>> edges) {
    Set<String> reached = new HashSet<>(seed);
    List<String> frontier = new LinkedList<>(seed);
    while (!frontier.isEmpty()) {
      for (String next : edges.getOrDefault(frontier.removeFirst(), Set.of())) {
        if (reached.add(next)) {
          frontier.add(next);
        }
      }
    }
    return reached;
  }

  // A retry point between an acquire-lock and the release-lock of the same key would re-run the
  // release without the acquire, or hold a lock the origin already released.
  private static void refuseInsideLock(
      String name,
      Map<String, WorkflowTask> tasksByName,
      Set<String> ancestors,
      Set<String> descendants) {
    for (String ancestor : ancestors) {
      WorkflowTask acquire = tasksByName.get(ancestor);
      if (acquire == null || !TaskType.acquirelock.equals(acquire.getType())) {
        continue;
      }
      Object key = ParameterUtil.getValue(acquire.getParams(), "key");
      for (String descendant : descendants) {
        WorkflowTask release = tasksByName.get(descendant);
        if (release != null
            && TaskType.releaselock.equals(release.getType())
            && Objects.equals(key, ParameterUtil.getValue(release.getParams(), "key"))) {
          throw retryInvalid(
              "Task " + name + " sits between acquirelock " + ancestor + " and releaselock "
                  + descendant + ".");
        }
      }
    }
  }

  private static BoomerangException retryInvalid(String detail) {
    return new BoomerangException(BoomerangError.WORKFLOWRUN_RETRY_INVALID, detail);
  }

  // Copies of the carried-over task runs under the new run, a for-each parent's items included.
  // The plan adopts each by name as if it had run (DAGUtility.createTaskList).
  private void carryOver(List<TaskRunEntity> carried, String newRunId) {
    for (TaskRunEntity origin : carried) {
      TaskRunEntity copy = taskRunRepository.save(reusedCopy(origin, newRunId, null));
      for (TaskRunEntity item : taskRunRepository.findByParentRefOrderByIndexAsc(origin.getId())) {
        taskRunRepository.save(reusedCopy(item, newRunId, copy.getId()));
      }
    }
  }

  // Everything the origin recorded, under a new id and run, pointing back at the origin. Claim,
  // deadline, retry and wait state stay absent: a copy is never claimable or reapable.
  private static TaskRunEntity reusedCopy(TaskRunEntity origin, String newRunId, String parentRef) {
    TaskRunEntity copy = new TaskRunEntity();
    copy.setType(origin.getType());
    copy.setName(origin.getName());
    copy.setLabels(new HashMap<>(origin.getLabels()));
    copy.setAnnotations(new HashMap<>(origin.getAnnotations()));
    copy.setCreationDate(origin.getCreationDate());
    copy.setStartTime(origin.getStartTime());
    copy.setDuration(origin.getDuration());
    copy.setTimeout(origin.getTimeout());
    copy.setParams(new LinkedList<>(origin.getParams()));
    copy.setResults(new LinkedList<>(origin.getResults()));
    copy.setWorkspace(origin.getWorkspace());
    copy.setSpec(origin.getSpec());
    copy.setStatus(origin.getStatus());
    copy.setPhase(origin.getPhase());
    copy.setStatusMessage(origin.getStatusMessage());
    copy.setStatusReason(origin.getStatusReason());
    copy.setPreApprovedStatus(origin.getPreApprovedStatus());
    copy.setDecisionValue(origin.getDecisionValue());
    copy.setDependencies(origin.getDependencies());
    copy.setTaskRef(origin.getTaskRef());
    copy.setTaskVersion(origin.getTaskVersion());
    copy.setWorkflowRef(origin.getWorkflowRef());
    copy.setWorkflowRevisionRef(origin.getWorkflowRevisionRef());
    copy.setWorkflowRunRef(newRunId);
    copy.setParentRef(parentRef);
    copy.setIndex(origin.getIndex());
    copy.setForeach(origin.getForeach());
    copy.setReusedFromRef(origin.getId());
    return copy;
  }

  /*
   * A child run's initiatedByRef is the submitting TaskRun; resolve the run that owns it so a
   * client can link back to the parent. Derived on read, exactly like workflowName, and only on
   * the single-run read - the query page has no use for it and would pay a lookup per row.
   */
  private void resolveInitiatingRun(WorkflowRunEntity wfRunEntity, WorkflowRun wfRun) {
    if (TriggerEnum.task.getTrigger().equals(wfRunEntity.getTrigger())
        && wfRunEntity.getInitiatedByRef() != null) {
      taskRunRepository
          .findById(wfRunEntity.getInitiatedByRef())
          .ifPresent(taskRun -> wfRun.setInitiatedByWorkflowRunRef(taskRun.getWorkflowRunRef()));
    }
  }

  // The triggers whose initiatedByRef is lineage (the schedule, the submitting task run, the origin
  // run) rather than who started the run.
  private static final Set<String> LINEAGE_TRIGGERS =
      Set.of(TriggerEnum.schedule.getTrigger(), TriggerEnum.task.getTrigger(), TriggerEnum.retry.getTrigger());

  /*
   * Name who started each run a person or a token started: a user id in initiatedByRef reads as
   * the user's display name, anything else (a token's name) as itself. One user lookup per page.
   */
  private void nameInitiators(List<WorkflowRun> wfRuns) {
    List<WorkflowRun> started =
        wfRuns.stream()
            .filter(r -> r.getInitiatedByRef() != null && !LINEAGE_TRIGGERS.contains(r.getTrigger()))
            .toList();
    if (started.isEmpty()) {
      return;
    }
    Map<String, String> names =
        userService.displayNamesByIds(started.stream().map(WorkflowRun::getInitiatedByRef).collect(Collectors.toSet()));
    started.forEach(r -> r.setInitiatedByName(names.getOrDefault(r.getInitiatedByRef(), r.getInitiatedByRef())));
  }

  private void updateWorkflowDetails(WorkflowRunEntity wfRunEntity, WorkflowRun wfRun) {
    // Set WorkflowName
    final Optional<WorkflowEntity> optWorkflow =
        workflowRepository.findById(wfRunEntity.getWorkflowRef());
    if (optWorkflow.isPresent()) {
      wfRun.setWorkflowName(optWorkflow.get().getName());
      wfRun.setWorkflowDisplayName(optWorkflow.get().getDisplayName());
    }
  }

  /*
   * Delivers an inbound event to the WorkflowRun's matching eventwait tasks
   */
  public void event(String workflowRunId, WorkflowRunEventRequest request) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REQ);
    }

    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }

    // Inbox dedup gate: the ledger insert is atomic on "<run>:<eventId>", so a transport
    // redelivery is acknowledged without being re-applied. Events without an id are not deduped.
    EventInboxEntity inbox = null;
    if (request.getId() != null && !request.getId().isBlank()) {
      inbox = new EventInboxEntity();
      inbox.setId(workflowRunId + ":" + request.getId());
      inbox.setTopic(request.getTopic());
      inbox.setRequestedStatus(request.getStatus());
      inbox.setReceivedAt(new Date());
      try {
        eventInboxRepository.insert(inbox);
      } catch (DuplicateKeyException e) {
        LOGGER.info(
            "[{}] Duplicate event {} already handled. Acknowledging without re-applying.",
            workflowRunId,
            request.getId());
        return;
      }
    }
    List<TaskRunEntity> taskRuns = taskRunRepository.findByWorkflowRunRef(workflowRunId);
    // Set preApproved or call endTaskRun for each with the status.
    List<TaskRunEntity> topicTaskRuns =
        taskRuns.stream()
            .filter(
                tr ->
                    TaskType.eventwait.equals(tr.getType())
                        && request
                            .getTopic()
                            .equals(ParameterUtil.getValue(tr.getParams(), "topic")))
            .toList();
    // Process the non waiting tasks first so as not to mess with the tree. This will only set
    // preApproved = true
    topicTaskRuns.stream()
        .filter(tr -> !RunStatus.waiting.equals(tr.getStatus()))
        .forEach(
            tr -> {
              LOGGER.debug("TaskRun Update: {}", tr.getName());
              // Field-scoped write - the status annotation, preApproved and the appended results,
              // nothing else. Saving `tr` whole reverted whatever a concurrent Compare-And-Set had
              // written to this TaskRun since the findByWorkflowRunRef page above was read.
              taskRunService.applyEventDelivery(
                  tr.getId(), request.getStatus(), request.getResults());
            });
    // Process the waiting tasks
    topicTaskRuns.stream()
        .filter(tr -> RunStatus.waiting.equals(tr.getStatus()))
        .forEach(
            tr -> {
              LOGGER.debug("TaskRun End: {}", tr.getName());
              TaskRunEndRequest endRequest = new TaskRunEndRequest();
              endRequest.setStatus(request.getStatus());
              endRequest.setResults(request.getResults());
              taskRunService.end(tr.getId(), Optional.of(endRequest));
            });

    if (inbox != null) {
      inbox.setStatus(InboxStatus.processed);
      inbox.setProcessedAt(new Date());
      eventInboxRepository.save(inbox);
    }
  }

  private List<TaskRun> getTaskRuns(String workflowRunId) {
    List<TaskRunEntity> taskRunEntities = taskRunRepository.findByWorkflowRunRef(workflowRunId);
    return taskRunEntities.stream().map(t -> new TaskRun(t)).collect(Collectors.toList());
  }

  private void logPayload(WorkflowRunRequest request) {
    // Names only: a param value may be a secret, typed or not yet stamped as one.
    LOGGER.info(
        "Received Request: params [{}], labels {}",
        ParameterUtil.runParamNames(request.getParams()),
        request.getLabels() == null ? "[]" : request.getLabels().keySet());
  }
}
