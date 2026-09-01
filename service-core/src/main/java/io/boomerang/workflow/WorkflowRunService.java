package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.boomerang.common.model.WorkflowRunResponsePage;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.*;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.FilterValuesOutputStream;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.event.entity.EventInboxEntity;
import io.boomerang.event.enums.InboxStatus;
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
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
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
import org.springframework.data.support.PageableExecutionUtils;
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
 * boundary that only existed because the engine used to be a separate deployable (DD-02).
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
  private final WorkflowExecutionService workflowExecutionService;
  private final TaskExecutionService taskExecutionService;
  private final EventInboxRepository eventInboxRepository;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;

  public WorkflowRunService(
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      ActionRepository actionRepository,
      TaskRunService taskRunService,
      WorkflowExecutionService workflowExecutionService,
      @Lazy TaskExecutionService taskExecutionService,
      EventInboxRepository eventInboxRepository,
      WorkflowRunStateHelper workflowRunStateHelper,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      ObjectMapper objectMapper) {
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.actionRepository = actionRepository;
    this.taskRunService = taskRunService;
    this.workflowExecutionService = workflowExecutionService;
    this.taskExecutionService = taskExecutionService;
    this.eventInboxRepository = eventInboxRepository;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.objectMapper = objectMapper;
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
  public ResponseEntity<WorkflowRun> get(String team, String workflowRunId, boolean withTasks) {
    requireWorkspaceRelationship(team, workflowRunId);
    WorkflowRun wfRun = get(workflowRunId, withTasks);
    filterSensitiveValues(wfRun);
    return ResponseEntity.ok(wfRun);
  }

  /*
   * Sensitive params are sensitive UPWARD (engine to UI/API consumer), so filtering happens on
   * the workspace-scoped v2 surface only - never on the unscoped reads the engine and dispatcher
   * use, which must see real values. Password-typed params (the workflow revision's param spec is
   * the type authority; RunParam carries no type on the wire) are blanked by name, and their
   * resolved values are scrubbed from task params, spec fields and results, where they can appear
   * under any name after substitution. Mutates the response model only.
   */
  void filterSensitiveValues(WorkflowRun wfRun) {
    if (wfRun == null || wfRun.getWorkflowRevisionRef() == null) {
      return;
    }
    workflowRevisionRepository
        .findById(wfRun.getWorkflowRevisionRef())
        .ifPresent(
            revision ->
                DataAdapterUtil.filterWorkflowRunValueByFieldType(
                    wfRun, revision.getParams(), FieldType.PASSWORD.value()));
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

    List<String> wfRefs = workspaceWorkflowRefs(queryTeam, queryWorkflows);
    // TODO query workflow runs
    if (wfRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
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
            Optional.empty(),
            Optional.of(wfRefs),
            queryTriggers);
    page.getContent().forEach(this::filterSensitiveValues);
    return new WorkflowRunResponsePage(
        page.getContent(), page.getPageable(), page.getTotalElements());
  }

  /*
   * Retrieve the insights / statistics for a specific period of time and filters
   */
  public WorkflowRunInsight insight(
      String queryTeam,
      Optional<Long> from,
      Optional<Long> to,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows) {
    List<String> wfRefs = workspaceWorkflowRefs(queryTeam, queryWorkflows);
    return insights(
        from.map(Date::new), to.map(Date::new), queryLabels, Optional.empty(), Optional.of(wfRefs));
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
    List<String> wfRefs = workspaceWorkflowRefs(queryTeam, queryWorkflows);
    return count(from.map(Date::new), to.map(Date::new), queryLabels, Optional.of(wfRefs));
  }

  /*
   * Start WorkflowRun
   *
   * TODO: do we expose this one?
   */
  public ResponseEntity<WorkflowRun> start(
      String team, String workflowRunId, Optional<WorkflowRunRequest> optRunRequest) {
    requireWorkspaceRelationship(team, workflowRunId);
    return ResponseEntity.ok(start(workflowRunId, optRunRequest));
  }

  /*
   * Finalize WorkflowRun
   *
   * TODO: do we expose this one?
   */
  public ResponseEntity<WorkflowRun> finalize(String team, String workflowRunId) {
    requireWorkspaceRelationship(team, workflowRunId);
    return ResponseEntity.ok(finalize(workflowRunId));
  }

  /*
   * Cancel WorkflowRun
   */
  public ResponseEntity<WorkflowRun> cancel(String team, String workflowRunId) {
    requireWorkspaceRelationship(team, workflowRunId);
    WorkflowRun wfRun = cancel(workflowRunId);
    // Close any Action (approval / manual task) the cancelled run left open. Inlined from the
    // deleted WorkspaceActionService.cancelAllByWorkflowRun, which was a one-line delegation to
    // this repository call and had no other caller.
    actionRepository.updateStatusByWorkflowRunRef(workflowRunId, ActionStatus.cancelled);
    return ResponseEntity.ok(wfRun);
  }

  /*
   * Pause WorkflowRun
   */
  public ResponseEntity<WorkflowRun> pause(String team, String workflowRunId) {
    requireWorkspaceRelationship(team, workflowRunId);
    return ResponseEntity.ok(pause(workflowRunId));
  }

  /*
   * Resume WorkflowRun
   */
  public ResponseEntity<WorkflowRun> resume(String team, String workflowRunId) {
    requireWorkspaceRelationship(team, workflowRunId);
    return ResponseEntity.ok(resume(workflowRunId));
  }

  /*
   * Retry WorkflowRun
   */
  public ResponseEntity<WorkflowRun> retry(String team, String workflowRunId) {
    requireWorkspaceRelationship(team, workflowRunId);

    // Refuse-first: an unresolvable owner fails the request BEFORE the clone is created, keeping
    // this operation all-or-nothing for a caller. The unscoped retry below records the ownership
    // edge itself, against this same resolution - never the `team` path segment, which only
    // proves the caller can reach this run through that path (and for a global-scope token
    // RelationshipService.check returns true for ANY path workspace), and once recorded wrong
    // owners permanently.
    owningWorkspace(workflowRunId);

    return ResponseEntity.ok(retry(workflowRunId, false, 1));
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
      throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
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
    StreamingResponseBody body = taskRunService.streamLog(taskRunId);
    // Sensitive-upward, the same rule filterSensitiveValues applies to the run payloads: a script
    // that echoes a password-typed param shows it in its log, so the stream is scrubbed at this
    // workspace-scoped surface. The dispatcher/engine never read logs through here.
    Set<String> secrets = taskRunSensitiveValues(taskRun.getWorkflowRunRef());
    if (secrets.isEmpty()) {
      return body;
    }
    return outputStream -> body.writeTo(new FilterValuesOutputStream(outputStream, secrets));
  }

  // The resolved values of the owning run's password-typed params, per DataAdapterUtil's
  // name-join against the workflow revision's param spec (the type authority).
  private Set<String> taskRunSensitiveValues(String workflowRunRef) {
    return workflowRunRepository
        .findById(workflowRunRef)
        .flatMap(
            run ->
                Optional.ofNullable(run.getWorkflowRevisionRef())
                    .flatMap(workflowRevisionRepository::findById)
                    .map(
                        revision ->
                            DataAdapterUtil.sensitiveValues(
                                revision.getParams(),
                                run.getParams(),
                                FieldType.PASSWORD.value())))
        .orElse(Set.of());
  }

  /**
   * The workspace that owns a WorkflowRun: its own recorded owner, or - for a run created without
   * an ownership edge, which is what the engine's auto-retry produces - the owner of the Workflow
   * it ran. The fallback is the same resolution {@code RelationshipEventListener
   * .onChildWorkflowRunCreated} and {@code ScheduleWatcher.resolveTeam} already use, and it keeps
   * a chained retry from failing on a missing parent.
   *
   * <p>When neither is recorded there is no third option: the {@code team} path segment is not an
   * owner, because a global-scope token passes {@link RelationshipService#check} for any workspace
   * (RelationshipService:417-419) - which is precisely how such a caller reaches a graph-orphaned
   * run - and adopting it is the wrong-owner bug this resolution exists to fix. So this refuses
   * with the mapped {@link BoomerangError#TEAM_INVALID_REF} (400) the rest of the product already
   * uses for an unresolvable workspace, rather than minting a run no workspace owns.
   */
  private String owningWorkspace(String workflowRunId) {
    String workspace = owningWorkspaceOrNull(workflowRunId);
    if (workspace == null) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    return workspace;
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
  private void requireWorkspaceRelationship(String team, String workflowRunId) {
    if (workflowRunId == null
        || workflowRunId.isBlank()
        || !relationshipService.check(
            RelationshipType.WORKFLOWRUN,
            workflowRunId,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)))) {
      // TODO: do we want to return invalid ref or unauthorized
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /** The Workflows of the given workspace that the caller may see, narrowed by the query filter. */
  private List<String> workspaceWorkflowRefs(
      String queryTeam, Optional<List<String>> queryWorkflows) {
    List<String> wfRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(queryTeam)),
            false);
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());
    return wfRefs;
  }

  // ── Unscoped operations (engine, webhook and v1 dispatcher callers) ────────


  public WorkflowRun get(String wfRunId, boolean withTasks) {
    if (wfRunId == null || wfRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    Optional<WorkflowRunEntity> wfRunEntity = workflowRunRepository.findById(wfRunId);
    if (wfRunEntity.isPresent()) {
      WorkflowRun wfRun = ConvertUtil.entityToModel(wfRunEntity.get(), WorkflowRun.class);
      updateWorkflowDetails(wfRunEntity.get(), wfRun);
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
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO add the ability to OR labels not just AND
    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryStatus.isPresent()) {
      if (queryStatus.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(RunStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryPhase.isPresent()) {
      if (queryPhase.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(RunPhase.class, q))) {
        Criteria criteria = Criteria.where("phase").in(queryPhase.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "phase");
      }
    }

    if (queryWorkflowRuns.isPresent()) {
      Criteria criteria = Criteria.where("id").in(queryWorkflowRuns.get());
      criteriaList.add(criteria);
    }

    if (queryWorkflows.isPresent()) {
      Criteria criteria = Criteria.where("workflowRef").in(queryWorkflows.get());
      criteriaList.add(criteria);
    }

    if (queryTriggers.isPresent()) {
      LOGGER.debug("Triggers: {}", queryTriggers.get().toString());
      Criteria criteria = Criteria.where("trigger").in(queryTriggers.get());
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    List<WorkflowRunEntity> wfRunEntities = mongoTemplate.find(query, WorkflowRunEntity.class);

    // Convert to WorkflowRun and add Workflow Name
    List<WorkflowRun> wfRuns = new LinkedList<>();
    wfRunEntities.forEach(
        e -> {
          WorkflowRun wfRun = ConvertUtil.entityToModel(e, WorkflowRun.class);
          updateWorkflowDetails(e, wfRun);
          wfRuns.add(wfRun);
        });

    Page<WorkflowRun> pages =
        PageableExecutionUtils.getPage(
            wfRuns,
            pageable,
            () -> mongoTemplate.count(Query.of(query).skip(-1).limit(-1), WorkflowRunEntity.class));

    return pages;
  }

  /*
   * Generates stats / insights for a given set of filters
   */
  public WorkflowRunInsight insights(
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> labels,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows) {
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO add the ability to OR labels not just AND
    if (labels.isPresent()) {
      labels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryWorkflows.isPresent()) {
      Criteria criteria = Criteria.where("workflowRef").in(queryWorkflows.get());
      criteriaList.add(criteria);
    } else {
      // TODO find all Workflows based on team, then
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    LOGGER.debug("Query: " + query.toString());
    List<WorkflowRunEntity> entities = mongoTemplate.find(query, WorkflowRunEntity.class);

    // Collect the Stats
    Long totalDuration = 0L;
    Long duration;

    for (WorkflowRunEntity entity : entities) {
      duration = entity.getDuration();
      if (duration != null) {
        totalDuration += duration;
      }
      // addActivityDetail(executions, activity);
    }

    WorkflowRunInsight wfRunInsight = new WorkflowRunInsight();
    wfRunInsight.setTotalRuns(Long.valueOf(entities.size()));
    wfRunInsight.setConcurrentRuns(
        entities.stream().filter(run -> RunPhase.running.equals(run.getPhase())).count());
    wfRunInsight.setTotalDuration(totalDuration);
    wfRunInsight.setMedianDuration(entities.size() != 0 ? totalDuration / entities.size() : 0L);
    List<WorkflowRunSummary> runs = new LinkedList<>();
    entities.forEach(
        e -> {
          WorkflowRunSummary summary = ConvertUtil.entityToModel(e, WorkflowRunSummary.class);
          final Optional<WorkflowEntity> optWorkflow =
              workflowRepository.findById(e.getWorkflowRef());
          if (optWorkflow.isPresent()) {
            summary.setWorkflowName(optWorkflow.get().getName());
          }
          runs.add(summary);
        });
    wfRunInsight.setRuns(runs);
    return wfRunInsight;
  }

  /*
   * Generates stats for a given set of filters
   */
  public WorkflowRunCount count(
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> labels,
      Optional<List<String>> queryWorkflows) {
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO add the ability to OR labels not just AND
    if (labels.isPresent()) {
      labels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryWorkflows.isPresent()) {
      Criteria criteria = Criteria.where("workflowRef").in(queryWorkflows.get());
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
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
   */
  public WorkflowRun run(WorkflowRunEntity wfRunEntity, boolean start) {
    workflowRunRepository.save(wfRunEntity);
    workflowExecutionService.queue(wfRunEntity.getId());

    if (start) {
      return this.start(wfRunEntity.getId(), Optional.empty());
    } else {
      // Retrieve the refreshed status
      return ConvertUtil.entityToModel(
          workflowRunRepository.findById(wfRunEntity.getId()).get(), WorkflowRun.class);
    }
  }

  public WorkflowRun start(String workflowRunId, Optional<WorkflowRunRequest> optRunRequest) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isPresent()) {
      WorkflowRunEntity wfRunEntity = optWfRunEntity.get();
      // Add values from Run Request
      if (optRunRequest.isPresent()) {
        logPayload(optRunRequest.get());
        wfRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
        wfRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
        wfRunEntity.setParams(
            ParameterUtil.addUniqueParams(
                wfRunEntity.getParams(), optRunRequest.get().getParams()));
        // Merge request workspaces by name so a run request cannot introduce a duplicate mount.
        optRunRequest
            .get()
            .getWorkspaces()
            .forEach(
                ws -> {
                  wfRunEntity
                      .getWorkspaces()
                      .removeIf(existing -> ws.getName().equals(existing.getName()));
                  wfRunEntity.getWorkspaces().add(ws);
                });
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

  public WorkflowRun finalize(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    final Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(workflowRunId);
    if (optWfRunEntity.isPresent()) {
      workflowExecutionService.end(workflowRunId);
      // Retrieve the refreshed status
      return ConvertUtil.entityToModel(
          workflowRunRepository.findById(workflowRunId).get(), WorkflowRun.class);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  public WorkflowRun cancel(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
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
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
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
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
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
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    // Compare-And-Set precondition: only a running run can be marked timed out - a late timeout
    // can never overwrite a terminal status. Only the winner drives the timeout to completion.
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
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
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
      wfRunEntity.setResults(new LinkedList<>());
      // Lineage on typed fields: initiatedByRef points at the first origin (preserved across
      // chained retries), trigger marks this run as a retry.
      if (!TriggerEnum.retry.getTrigger().equals(wfRunEntity.getTrigger())) {
        wfRunEntity.setInitiatedByRef(workflowRunId);
        wfRunEntity.setTrigger(TriggerEnum.retry.getTrigger());
      }
      workflowRunRepository.save(wfRunEntity);

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

  private void updateWorkflowDetails(WorkflowRunEntity wfRunEntity, WorkflowRun wfRun) {
    // Set WorkflowName
    final Optional<WorkflowEntity> optWorkflow =
        workflowRepository.findById(wfRunEntity.getWorkflowRef());
    if (optWorkflow.isPresent()) {
      wfRun.setWorkflowName(optWorkflow.get().getName());
      wfRun.setWorkflowDisplayName(optWorkflow.get().getDisplayName());
    }
    // Remove Annotations
    // TODO determine if this should be done elsewhere
    wfRun.getAnnotations().remove("boomerang.io/global-params");
    wfRun.getAnnotations().remove("boomerang.io/context-params");
    wfRun.getAnnotations().remove("boomerang.io/workspace-params");
  }

  /*
   * Delivers an inbound event to the WorkflowRun's matching eventwait tasks
   */
  public void event(String workflowRunId, WorkflowRunEventRequest request) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
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

    //
    // TODO: Update the following or make sure they are set on the run at execution end task time.
    // if (TaskType.approval.equals(run.getTaskType())
    // || TaskType.manual.equals(run.getTaskType())) {
    // Action approval = approvalService.getApprovalByTaskActivits(task.getId());
    // response.setApproval(approval);
    // } else if (TaskType.runworkflow == task.getTaskType()
    // && task.getRunWorkflowActivityId() != null) {
    //
    // String runWorkflowActivityId = task.getRunWorkflowActivityId();
    // ActivityEntity activity =
    // this.flowActivityService.findWorkflowActivtyById(runWorkflowActivityId);
    // if (activity != null) {
    // response.setRunWorkflowActivityStatus(activity.getStatus());
    // }
    // } else if (TaskType.eventwait == task.getTaskType()) {
    // List<TaskOutputResult> results = new LinkedList<>();
    // TaskOutputResult result = new TaskOutputResult();
    // result.setName("eventPayload");
    // result.setDescription("Payload that was received with the Wait For Event");
    // if (task.getOutputs() != null) {
    // String json = task.getOutputs().get("eventPayload");
    // result.setValue(json);
    // }
    // results.add(result);
    // response.setResults(results);
    // } else if (TaskType.template == task.getTaskType()
    // || TaskType.customtask == task.getTaskType() || TaskType.script == task.getTaskType()) {
    // List<TaskOutputResult> results = new LinkedList<>();
    // setupTaskOutputResults(task, response, results);
    //
    // }
  }

  private void logPayload(WorkflowRunRequest request) {
    try {
      String payload = objectMapper.writeValueAsString(request);
      LOGGER.info("Received Request Payload: ");
      LOGGER.info(payload);
    } catch (JacksonException e) {
      LOGGER.error(e.getStackTrace());
    }
  }
}
