package io.boomerang.dispatcher;

import static io.boomerang.workflow.ConvertUtil.entityToModel;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkspaceReleaseQuery;
import io.boomerang.common.model.WorkspaceReleaseResponse;
import io.boomerang.common.model.StatusReason;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.engine.ParameterManager;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.model.ClaimFilter;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.workflow.ArtifactService;
import io.boomerang.workflow.TaskService;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class DispatcherService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final Integer MAX_POLL_INTERVAL = 30000;
  private static final Integer MAX_SLEEP_INTERVAL = 1000; // 1 sec
  private static final int PAGE_SIZE = 20;

  // One release query answers for at most this many owners of each kind. The dispatcher holds a
  // bounded set of volumes per reconciliation pass and pages anything larger.
  private static final int MAX_RELEASE_REFS = 500;

  // Kill switch: stops CLAIMING only. The watcher's recovery sweeps are never gated by it.
  @Value("${flow.queue.enabled:true}")
  private boolean queueEnabled;

  private final DispatcherRepository dispatcherRepository;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final TaskRunService taskRunService;
  private final MongoTemplate mongoTemplate;
  private final ArtifactService artifactService;
  private final ClaimFilterService claimFilterService;
  private final ParameterManager parameterManager;
  private final WorkflowRunRepository workflowRunRepository;
  private final TaskService taskService;

  public DispatcherService(
      DispatcherRepository dispatcherRepository,
      WorkflowRunStateHelper workflowRunStateHelper,
      TaskRunService taskRunService,
      MongoTemplate mongoTemplate,
      ArtifactService artifactService,
      ClaimFilterService claimFilterService,
      ParameterManager parameterManager,
      WorkflowRunRepository workflowRunRepository,
      TaskService taskService) {
    this.dispatcherRepository = dispatcherRepository;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.taskRunService = taskRunService;
    this.mongoTemplate = mongoTemplate;
    this.artifactService = artifactService;
    this.claimFilterService = claimFilterService;
    this.parameterManager = parameterManager;
    this.workflowRunRepository = workflowRunRepository;
    this.taskService = taskService;
  }

  /** The start answer, with the task reference in its slug form. */
  public ResponseEntity<TaskRun> startTaskRun(
      String taskRunId, Optional<TaskRunStartRequest> request) {
    return slugTaskRef(taskRunService.start(taskRunId, request));
  }

  /** The end answer, with the task reference in its slug form. */
  public ResponseEntity<TaskRun> endTaskRun(String taskRunId, Optional<TaskRunEndRequest> request) {
    return slugTaskRef(taskRunService.end(taskRunId, request));
  }

  private ResponseEntity<TaskRun> slugTaskRef(ResponseEntity<TaskRun> answer) {
    if (answer.getBody() != null) {
      slugTaskRefs(List.of(answer.getBody()));
    }
    return answer;
  }

  // On the protocol a task reference is the task's slug form, resolved once per batch; a task the
  // catalogue no longer holds keeps its id.
  private void slugTaskRefs(List<TaskRun> taskRuns) {
    Map<String, String> slugs =
        taskService.slugsByRef(
            taskRuns.stream().map(TaskRun::getTaskRef).filter(Objects::nonNull).toList());
    taskRuns.forEach(
        taskRun -> taskRun.setTaskRef(slugs.getOrDefault(taskRun.getTaskRef(), taskRun.getTaskRef())));
  }

  /**
   * Registers the Dispatcher
   *
   * <p>This method saves the dispatcher's details to the database. This can be used in the future to
   * remove a dispatcher and its token that we no longer trust
   *
   * <p>TODO: expand capabilities to allow revoking a dispatcher's registration and also storing the
   * token that was used.
   *
   * @param request
   * @return the ID of the registered dispatcher
   */
  public String register(DispatcherRegistrationRequest request) {
    if (request == null || request.getHost() == null || request.getHost().isEmpty()) {
      throw new BoomerangException(BoomerangError.DISPATCHER_INVALID_REQ);
    }

    // Idempotent registration: a dispatcher re-registering with the same name and host refreshes its
    // record instead of colliding on the unique index. The log source is replaced whole: a
    // dispatcher that stops advertising one clears what it registered before.
    Update update =
        new Update()
            .set("taskTypes", TaskType.convertToTaskTypeList(request.getTaskTypes()))
            .set("version", request.getVersion())
            .set("lastConnectedDate", new Date())
            .setOnInsert("creationDate", new Date());
    setOrUnset(update, "logsUrl", request.getLogsUrl());
    setOrUnset(update, "logsToken", request.getLogsToken());
    DispatcherEntity entity =
        mongoTemplate.findAndModify(
            Query.query(
                Criteria.where("name").is(request.getName()).and("host").is(request.getHost())),
            update,
            new FindAndModifyOptions().upsert(true).returnNew(true),
            DispatcherEntity.class);

    // Log the registration for debugging purposes
    LOGGER.debug(
        "Registered dispatcher: {}({}) with task types: {}",
        entity.getId(),
        entity.getName(),
        request.getTaskTypes());

    return entity.getId();
  }

  private static void setOrUnset(Update update, String field, String value) {
    if (value == null || value.isBlank()) {
      update.unset(field);
    } else {
      update.set(field, value);
    }
  }

  // Marks the dispatcher connected now and reads its task types, in one round trip; null when it is
  // not registered.
  private DispatcherEntity connect(String dispatcherId) {
    Query query = Query.query(Criteria.where("_id").is(dispatcherId));
    query.fields().include("taskTypes");
    return mongoTemplate.findAndModify(
        query,
        new Update().set("lastConnectedDate", new Date()),
        FindAndModifyOptions.options().returnNew(true),
        DispatcherEntity.class);
  }

  // Marks the dispatcher connected now, in one round trip; false when it is not registered.
  private boolean markConnected(String dispatcherId) {
    return mongoTemplate
            .updateFirst(
                Query.query(Criteria.where("_id").is(dispatcherId)),
                new Update().set("lastConnectedDate", new Date()),
                DispatcherEntity.class)
            .getMatchedCount()
        > 0;
  }

  /**
   * Renews the lease for the TaskRun ids this dispatcher's executor threads are still working on,
   * and refreshes its liveness timestamp. The claim sets the first lease ({@link
   * io.boomerang.engine.TaskRunService#tryClaim}), so a claim left out of every heartbeat is
   * reaped once that lease lapses.
   *
   * @param dispatcherId the dispatcher sending the heartbeat
   * @param ids TaskRun ids the dispatcher still owns
   * @return the number of leases actually renewed
   */
  public long heartbeat(String dispatcherId, List<String> ids) {
    if (!markConnected(dispatcherId)) {
      LOGGER.error("Dispatcher {} not registered", dispatcherId);
      throw new BoomerangException(BoomerangError.DISPATCHER_NOT_REGISTERED, dispatcherId);
    }
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    long renewed = taskRunService.renewLeases(ids, dispatcherId);
    LOGGER.debug("Renewed {} of {} lease(s) for dispatcher {}.", renewed, ids.size(), dispatcherId);
    return renewed;
  }

  /**
   * Long-poll endpoint dispatching WorkflowRuns to the dispatcher.
   *
   * <p>Each cycle pages the runs awaiting workspace provisioning and claims each one individually
   * via a Compare-And-Set; racing dispatchers cannot both win a run and the response contains only the
   * documents this dispatcher actually claimed. Claimed and terminal runs are not redelivered. Releasing
   * a run's storage is not dispatched here - the dispatcher reconciles what it holds against {@link
   * #releasable}.
   *
   * @param dispatcherId
   * @return
   */
  public ResponseEntity<List<WorkflowRun>> getWorkflowQueue(String dispatcherId) {
    if (!queueEnabled) {
      LOGGER.warn("Queue claiming disabled (flow.queue.enabled=false). Returning no content.");
      return ResponseEntity.noContent().build();
    }
    // Validate the Dispatcher
    if (!markConnected(dispatcherId)) {
      LOGGER.error("Dispatcher {} not registered", dispatcherId);
      throw new BoomerangException(BoomerangError.DISPATCHER_NOT_REGISTERED, dispatcherId);
    }
    // TODO add in future filtering of workflows based on labels or a setting

    // Long poll logic
    Instant endTime = Instant.now().plusMillis(MAX_POLL_INTERVAL); // Keep connection open
    LOGGER.debug("Starting long poll queue for dispatcher: {}", dispatcherId);
    while (Instant.now().isBefore(endTime)) {
      LOGGER.debug("Checking queue for dispatcher: {}", dispatcherId);
      // The claimed pre-images carry the wire shape the dispatcher acts on: pending/ready to
      // provision and start. Runs claimed before an error are still handed out.
      List<WorkflowRun> workflowRuns = new LinkedList<>();
      try {
        for (WorkflowRunEntity candidate : workflowRunStateHelper.findClaimableForProvision(PAGE_SIZE)) {
          WorkflowRunEntity claimed =
              workflowRunStateHelper.tryClaimForProvision(candidate.getId(), dispatcherId);
          if (claimed != null) {
            // A run that cannot be handed out keeps its provisioning claim until the stale-claim
            // sweep releases it; the rest of the page still goes out.
            try {
              workflowRuns.add(entityToModel(claimed, WorkflowRun.class));
            } catch (RuntimeException e) {
              LOGGER.error("[{}] The claimed workflow run could not be handed out", claimed.getId(), e);
            }
          }
        }
      } catch (Exception e) {
        LOGGER.error("Error retrieving workflows for dispatcher {}", dispatcherId, e);
      }

      LOGGER.debug("Claimed {} WorkflowRuns for Dispatcher: {}", workflowRuns.size(), dispatcherId);
      if (!workflowRuns.isEmpty()) {
        return ResponseEntity.ok(workflowRuns);
      }
      if (!pauseBeforeRecheck()) {
        break;
      }
    }
    LOGGER.debug("Ending long poll queue for dispatcher: {}", dispatcherId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Long-poll endpoint dispatching TaskRuns to the dispatcher.
   *
   * <p>Each cycle pages two sets of candidates, both restricted to the dispatcher's registered task
   * types: the ready/pending unclaimed runs to execute, and the runs a dispatcher still owns whose
   * executor-side work must be terminated - either because the node finished cancelled/timedout,
   * or because it timed out with retry budget left and is parked mid-retry until its previous pod
   * is gone. Each candidate is claimed individually via a Compare-And-Set, so racing dispatchers cannot
   * both win a TaskRun and the response contains only the documents this dispatcher actually claimed.
   * Neither path redelivers: an execution claim takes ownership, a termination claim releases it.
   *
   * @param dispatcherId
   * @return
   */
  /*
   * An artifact task leaves with its link params filled. A refusal (bad or taken name, storage
   * full, artifact expired) ends the task failed with the reason instead of handing it out; an
   * optional download with nothing to fetch ends succeeded with found=false, likewise unhandled.
   */
  private boolean fillArtifactLink(TaskRun taskRun) {
    try {
      if (artifactService.fillLinkParams(taskRun) == ArtifactService.LinkOutcome.NOT_FOUND) {
        TaskRunEndRequest notFound = new TaskRunEndRequest();
        notFound.setStatus(RunStatus.succeeded);
        notFound.setStatusReason(StatusReason.ARTIFACT_NOT_FOUND);
        notFound.setStatusMessage(
            "No artifact of that name is available; the download is optional.");
        notFound.setResults(List.of(new RunResult(ArtifactService.FOUND_RESULT, "false")));
        taskRunService.end(taskRun.getId(), Optional.of(notFound));
        return false;
      }
      return true;
    } catch (BoomerangException e) {
      TaskRunEndRequest refused = new TaskRunEndRequest();
      refused.setStatus(RunStatus.failed);
      refused.setStatusReason(StatusReason.ARTIFACT_REFUSED);
      refused.setStatusMessage(artifactService.refusalMessage(e));
      taskRunService.end(taskRun.getId(), Optional.of(refused));
      return false;
    }
  }

  /**
   * Long-poll for TaskRuns, claiming at most {@code limit} new ones to execute - the slots the
   * dispatcher has free - and never more than a page. A limit of 0 claims none and still hands out
   * termination orders, which free slots rather than take them; no limit means a full page. The
   * poll is narrowed by optional filters: task types, task slugs, and a label on the workflow
   * definition (see {@link ClaimFilterService}). Termination orders follow the same filters. A
   * filter that matches nothing answers at once with no content.
   */
  public ResponseEntity<List<TaskRun>> getTaskQueue(
      String dispatcherId, Integer limit, String type, String task, String workflowLabel) {
    if (!queueEnabled) {
      LOGGER.warn("Queue claiming disabled (flow.queue.enabled=false). Returning no content.");
      return ResponseEntity.noContent().build();
    }
    // Validate the Dispatcher
    DispatcherEntity entity = connect(dispatcherId);
    if (entity == null) {
      LOGGER.error("Dispatcher {} not registered", dispatcherId);
      throw new BoomerangException(BoomerangError.DISPATCHER_NOT_REGISTERED, dispatcherId);
    }
    if (entity.getTaskTypes() != null && entity.getTaskTypes().isEmpty()) {
      LOGGER.warn("Dispatcher {} has no task types defined. Returning 204.", dispatcherId);
      return ResponseEntity.noContent().build();
    }

    LOGGER.debug("Entity: {}", entity);
    int executionPage = (limit == null) ? PAGE_SIZE : Math.min(PAGE_SIZE, Math.max(0, limit));
    ClaimFilter filter =
        claimFilterService.resolve(entity.getTaskTypes(), type, task, workflowLabel);
    if (filter.matchesNothing()) {
      LOGGER.debug("Dispatcher {} poll filters match no TaskRun. Returning 204.", dispatcherId);
      return ResponseEntity.noContent().build();
    }

    // Long poll logic
    Instant endTime =
        Instant.now().plusMillis(MAX_POLL_INTERVAL); // Keep connection open for 30 seconds
    LOGGER.debug("Starting long poll queue for dispatcher: {}", dispatcherId);
    while (Instant.now().isBefore(endTime)) {
      LOGGER.debug(
          "Checking queue for dispatcher: {} with task types: {}", dispatcherId, filter.types());
      // Page then claim: the Compare-And-Set re-checks eligibility per document, so a candidate
      // another dispatcher claimed between page and claim is simply skipped. The returned pre-images
      // carry the pending/ready wire shape the dispatcher executes. Runs claimed before an error
      // are still handed out, so no claim is left with a dispatcher that never hears of it.
      List<TaskRun> taskRuns = new LinkedList<>();
      try {
        for (TaskRunEntity candidate :
            (executionPage > 0)
                ? taskRunService.findClaimable(filter, executionPage)
                : List.<TaskRunEntity>of()) {
          TaskRunEntity claimed =
              taskRunService.tryClaim(candidate.getId(), candidate.getTimeout(), dispatcherId);
          if (claimed != null) {
            TaskRun taskRun = prepare(claimed);
            if (taskRun != null) {
              taskRuns.add(taskRun);
            }
          }
        }
        // Second path, mirroring the WorkflowRun provision/teardown pair: a TaskRun a dispatcher
        // still owns carries executor-side work (a Tekton TaskRun and its pod) that has to be
        // terminated - the node either finished cancelled/timedout, or is parked mid-retry. The
        // claim release is what makes it one dispatcher's job, stops the run being redelivered on the
        // next poll, and (for a retry) is what re-arms the node for its next attempt.
        for (TaskRunEntity candidate :
            taskRunService.findClaimableForTermination(filter, PAGE_SIZE)) {
          TaskRunEntity claimed =
              taskRunService.tryClaimForTermination(candidate.getId(), dispatcherId);
          if (claimed != null) {
            taskRuns.add(new TaskRun(claimed));
          }
        }
      } catch (Exception e) {
        LOGGER.error("Error retrieving tasks for dispatcher {}", dispatcherId, e);
      }

      LOGGER.debug("Claimed {} TaskRuns for Dispatcher: {}", taskRuns.size(), dispatcherId);
      if (!taskRuns.isEmpty()) {
        slugTaskRefs(taskRuns);
        return ResponseEntity.ok(taskRuns);
      }
      if (!pauseBeforeRecheck()) {
        break;
      }
    }
    LOGGER.debug("Ending long poll queue for dispatcher: {}", dispatcherId);
    return ResponseEntity.noContent().build();
  }

  /**
   * The run order for a task this poll just claimed, or null when it was ended instead. A failure
   * while preparing it ends the task failed with {@code DispatchPrepareFailed} rather than leaving
   * it claimed by a dispatcher that never hears of it, and the rest of the batch still goes out.
   */
  private TaskRun prepare(TaskRunEntity claimed) {
    try {
      TaskRun taskRun = new TaskRun(claimed);
      if (!fillSpec(claimed, taskRun)) {
        return null;
      }
      if (ArtifactService.isArtifactTask(claimed.getType()) && !fillArtifactLink(taskRun)) {
        return null;
      }
      return taskRun;
    } catch (RuntimeException e) {
      LOGGER.error("[{}] The claimed task could not be prepared for hand-out", claimed.getId(), e);
      TaskRunEndRequest failed = new TaskRunEndRequest();
      failed.setStatus(RunStatus.failed);
      failed.setStatusReason(StatusReason.DISPATCH_PREPARE_FAILED);
      failed.setStatusMessage("The task could not be prepared for its dispatcher: " + e.getMessage());
      taskRunService.end(claimed.getId(), Optional.of(failed));
      return null;
    }
  }

  /**
   * Put the claimed task's spec, with its references filled in, on the run order. The stored spec
   * keeps its references; only the copy handed to the dispatcher carries values. A termination order, and the start and
   * end answers, carry the stored spec, as nothing runs from them.
   */
  private boolean fillSpec(TaskRunEntity claimed, TaskRun taskRun) {
    try {
      TaskRunSpec filled =
          workflowRunRepository
              .findById(claimed.getWorkflowRunRef())
              .map(run -> parameterManager.fillSpec(run, claimed))
              .orElse(claimed.getSpec());
      taskRun.setSpec(filled);
      return true;
    } catch (RuntimeException e) {
      // Handing out the stored references would run the wrong script, and dropping the task would
      // leave it claimed by a dispatcher that never heard of it: it ends failed with the reason.
      LOGGER.error("[{}] The task's spec could not be filled in: {}", claimed.getId(), e.getMessage());
      TaskRunEndRequest failed = new TaskRunEndRequest();
      failed.setStatus(RunStatus.failed);
      failed.setStatusReason(StatusReason.SPEC_FILL_FAILED);
      failed.setStatusMessage("The task's spec references could not be filled in at claim.");
      taskRunService.end(claimed.getId(), Optional.of(failed));
      return false;
    }
  }

  /**
   * Wait one re-check interval before the poll looks again - after an error too, so a query that
   * fails fast is not re-run in a tight loop for the rest of the window. Return false when the
   * thread is interrupted, so the poll ends and the thread can stop.
   */
  private static boolean pauseBeforeRecheck() {
    try {
      Thread.sleep(MAX_SLEEP_INTERVAL);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Answer which of the runs a dispatcher still holds workspace volumes for are finished, so those
   * volumes can be released. A {@code workflowRunRef} is finished once its run is completed or
   * gone. The run record carries nothing about release - the cluster is the source of truth for
   * what exists, the engine for what is finished, and a dispatcher that asks twice gets the same
   * answer.
   *
   * @param query the runs the dispatcher still holds volumes for
   * @return the subset whose volumes may go
   */
  public WorkspaceReleaseResponse releasable(WorkspaceReleaseQuery query) {
    List<String> workflowRunRefs = capped(query.getWorkflowRunRefs(), "workflowRunRefs");

    // Held = the run still exists in a state the dispatcher must keep storage for. Everything
    // the dispatcher asked about and did not get back as held is releasable, which is what makes
    // an id the engine has never heard of release rather than leak.
    Set<String> heldRuns =
        mongoTemplate
            .find(
                idsOnly(
                    Criteria.where("_id").in(workflowRunRefs).and("phase").ne(RunPhase.completed)),
                WorkflowRunEntity.class)
            .stream()
            .map(WorkflowRunEntity::getId)
            .collect(Collectors.toSet());

    WorkspaceReleaseResponse response = new WorkspaceReleaseResponse();
    response.setWorkflowRunRefs(minus(workflowRunRefs, heldRuns));
    LOGGER.debug(
        "Releasable: {} of {} WorkflowRun owners.",
        response.getWorkflowRunRefs().size(),
        workflowRunRefs.size());
    return response;
  }

  private static List<String> capped(List<String> refs, String field) {
    if (refs == null) {
      return List.of();
    }
    if (refs.size() > MAX_RELEASE_REFS) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, field);
    }
    return refs;
  }

  // Projected to _id alone: this answers a set-membership question, never a document read.
  private static Query idsOnly(Criteria criteria) {
    Query query = Query.query(criteria);
    query.fields().include("_id");
    return query;
  }

  private static List<String> minus(List<String> refs, Set<String> held) {
    return refs.stream().filter(ref -> !held.contains(ref)).toList();
  }
}
