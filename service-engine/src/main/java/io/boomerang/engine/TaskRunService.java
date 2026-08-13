package io.boomerang.engine;

import io.boomerang.client.LogClient;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.util.ParameterUtil;
import io.boomerang.util.ResultUtil;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/*
 * Handles CRUD of TaskRuns
 */
@Service
public class TaskRunService {
  private static final Logger LOGGER = LogManager.getLogger();

  // Grace added on top of the timeout budget so a run at exactly its budget is not reaped.

  private final TaskExecutionService taskExecutionService;
  private final LogClient logClient;
  private final TaskRunRepository taskRunRepository;
  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  public TaskRunService(
      @Lazy TaskExecutionService taskExecutionService,
      @Lazy LogClient logClient,
      TaskRunRepository taskRunRepository,
      MongoTemplate mongoTemplate,
      ApplicationEventPublisher eventPublisher) {
    this.taskExecutionService = taskExecutionService;
    this.logClient = logClient;
    this.taskRunRepository = taskRunRepository;
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  // Return the page of TaskRuns eligible for claiming by an executor of the given types: ready,
  // pending, unclaimed, with any retry backoff elapsed, oldest first.
  public List<TaskRunEntity> findClaimable(List<TaskType> types, int limit) {
    Criteria criteria =
        Criteria.where(TaskRunEntity.Fields.STATUS)
            .is(RunStatus.ready)
            .and(TaskRunEntity.Fields.PHASE)
            .is(RunPhase.pending)
            .and(TaskRunEntity.Fields.TYPE)
            .in(types)
            .and(TaskRunEntity.Fields.CLAIM_BY)
            .exists(false)
            .orOperator(
                Criteria.where(TaskRunEntity.Fields.RETRY_AFTER).exists(false),
                Criteria.where(TaskRunEntity.Fields.RETRY_AFTER).lte(new Date()));
    Query query =
        Query.query(excludePausedRuns(criteria))
            .with(Sort.by(Sort.Direction.ASC, TaskRunEntity.Fields.CREATION_DATE))
            .limit(limit);
    // The claim page only needs the id - tryClaim re-reads and transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Two-step join for pause exclusion: the paused flag lives only on the WorkflowRun (indexed,
  // id-only fetch) - task_runs never carry a paused field. Callers must use the returned
  // criteria: it is the chain's last link, which is what serializes the whole chain.
  private Criteria excludePausedRuns(Criteria criteria) {
    Query pausedRuns = Query.query(Criteria.where(WorkflowRunEntity.Fields.PAUSE_REQUESTED_AT).exists(true));
    pausedRuns.fields().include("_id");
    List<String> pausedRunIds =
        mongoTemplate.find(pausedRuns, WorkflowRunEntity.class).stream()
            .map(WorkflowRunEntity::getId)
            .toList();
    if (pausedRunIds.isEmpty()) {
      return criteria;
    }
    return criteria.and(TaskRunEntity.Fields.WORKFLOW_RUN_REF).nin(pausedRunIds);
  }

  // Claim Compare-And-Set: re-checks full eligibility between page and claim; racing claimants
  // cannot both win. Returns the pre-image, or null when another claimant won.
  public TaskRunEntity tryClaim(String id, String claimedBy) {
    Date now = new Date();
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and(TaskRunEntity.Fields.STATUS)
                .is(RunStatus.ready)
                .and(TaskRunEntity.Fields.PHASE)
                .is(RunPhase.pending)
                .and(TaskRunEntity.Fields.CLAIM_BY)
                .exists(false)
                .orOperator(
                    Criteria.where(TaskRunEntity.Fields.RETRY_AFTER).exists(false),
                    Criteria.where(TaskRunEntity.Fields.RETRY_AFTER).lte(now)));
    Update update =
        new Update()
            .set(TaskRunEntity.Fields.PHASE, RunPhase.queued)
            .set(TaskRunEntity.Fields.CLAIM_BY, claimedBy)
            .set(TaskRunEntity.Fields.CLAIM_AT, now)
            .set(TaskRunEntity.Fields.AGENT_REF, claimedBy)
            .inc(TaskRunEntity.Fields.CLAIM_SEQ, 1)
            .unset(TaskRunEntity.Fields.RETRY_AFTER);
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setAgentRef(claimedBy);
    }
    return preImage;
  }

  // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params
  // in the same guarded write. Returns the pre-image, or null when already admitted.
  public TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and(TaskRunEntity.Fields.STATUS)
                .is(RunStatus.notstarted)
                .and(TaskRunEntity.Fields.PHASE)
                .is(RunPhase.pending));
    Update update = new Update().set(TaskRunEntity.Fields.STATUS, RunStatus.ready).set(TaskRunEntity.Fields.PARAMS, resolvedParams);
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

  // Execution-entry Compare-And-Set: ready + pending/queued becomes running with the given start
  // time, baking timeoutAt from the given budget. Returns the document with the transition
  // applied, or null on a duplicate dispatch.
  public TaskRunEntity tryStartExecution(String id, Date startTime, Long timeoutMinutes) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and(TaskRunEntity.Fields.STATUS)
                .is(RunStatus.ready)
                .and(TaskRunEntity.Fields.PHASE)
                .in(RunPhase.pending, RunPhase.queued));
    Update update =
        new Update()
            .set(TaskRunEntity.Fields.STATUS, RunStatus.running)
            .set(TaskRunEntity.Fields.PHASE, RunPhase.running)
            .set(TaskRunEntity.Fields.START_TIME, startTime);
    Date timeoutAt = RunTimeouts.deadline(startTime, timeoutMinutes);
    if (timeoutAt != null) {
      update.set(TaskRunEntity.Fields.TIMEOUT_AT, timeoutAt);
    }
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage == null) {
      return null;
    }
    publish(preImage, RunStatus.running, RunPhase.running);
    // Return the pre-image with the transition applied - the caller executes on the new state.
    preImage.setStatus(RunStatus.running);
    preImage.setPhase(RunPhase.running);
    preImage.setStartTime(startTime);
    preImage.setTimeoutAt(timeoutAt);
    return preImage;
  }

  // Completion Compare-And-Set: any non-completed phase becomes completed; status/statusMessage
  // set only when provided; claimant identity enforced as fencing when provided. Returns the
  // pre-image, or null when already completed - a terminal status is never overwritten.
  public TaskRunEntity tryComplete(
      String id,
      Optional<RunStatus> status,
      Optional<String> statusMessage,
      long duration,
      Optional<String> claimedBy,
      Optional<Long> claimSeq) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and(TaskRunEntity.Fields.PHASE)
            .in(RunPhase.pending, RunPhase.queued, RunPhase.running);
    claimedBy.ifPresent(by -> criteria.and(TaskRunEntity.Fields.CLAIM_BY).is(by));
    claimSeq.ifPresent(seq -> criteria.and(TaskRunEntity.Fields.CLAIM_SEQ).is(seq));
    Update update =
        new Update()
            .set(TaskRunEntity.Fields.PHASE, RunPhase.completed)
            .set(TaskRunEntity.Fields.DURATION, duration)
            .unset(TaskRunEntity.Fields.TIMEOUT_AT);
    status.ifPresent(s -> update.set(TaskRunEntity.Fields.STATUS, s));
    statusMessage.ifPresent(m -> update.set(TaskRunEntity.Fields.STATUS_MESSAGE, m));
    TaskRunEntity preImage =
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, status.orElse(preImage.getStatus()), RunPhase.completed);
    }
    return preImage;
  }

  // Return the page of TaskRuns whose deadline has passed: timeoutAt due, phase queued/running.
  public List<TaskRunEntity> findReapable(Date now, int limit) {
    Criteria criteria =
        Criteria.where(TaskRunEntity.Fields.TIMEOUT_AT).lte(now).and(TaskRunEntity.Fields.PHASE).in(RunPhase.queued, RunPhase.running);
    // Tasks of paused runs are not reaped; their deadlines stand and are reaped on resume.
    Query query =
        Query.query(excludePausedRuns(criteria))
            .with(Sort.by(Sort.Direction.ASC, TaskRunEntity.Fields.TIMEOUT_AT))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Timeout Compare-And-Set: a queued/running TaskRun past its deadline gets status timedout and
  // the message in the same atomic write, fenced on the observed claim seq. Returns the
  // pre-image, or null when fenced out or already transitioned.
  public TaskRunEntity tryTimeout(String id, Long observedClaimSeq, String statusMessage) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and(TaskRunEntity.Fields.PHASE)
            .in(RunPhase.queued, RunPhase.running)
            .and(TaskRunEntity.Fields.TIMEOUT_AT)
            .lte(new Date());
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set(TaskRunEntity.Fields.STATUS, RunStatus.timedout)
            .set(TaskRunEntity.Fields.STATUS_MESSAGE, statusMessage)
            .unset(TaskRunEntity.Fields.TIMEOUT_AT);
    TaskRunEntity preImage =
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  // Requeue Compare-And-Set: clears claim.by/claim.at/claim.leaseExpiresAt (never claim.seq) and
  // the baked deadline, writes the retry block and parks the TaskRun back at ready/pending.
  // Fenced on the observed claim seq. Returns the pre-image, or null when fenced/already gone.
  public TaskRunEntity tryRequeue(String id, Long observedClaimSeq, Date retryAfter, int retryCount) {
    Criteria criteria =
        Criteria.where("_id").is(id).and(TaskRunEntity.Fields.PHASE).in(RunPhase.queued, RunPhase.running);
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set(TaskRunEntity.Fields.STATUS, RunStatus.ready)
            .set(TaskRunEntity.Fields.PHASE, RunPhase.pending)
            .set(TaskRunEntity.Fields.RETRY_AFTER, retryAfter)
            .set(TaskRunEntity.Fields.RETRY_COUNT, retryCount)
            .unset(TaskRunEntity.Fields.CLAIM_BY)
            .unset(TaskRunEntity.Fields.CLAIM_AT)
            .unset(TaskRunEntity.Fields.CLAIM_LEASE_EXPIRES_AT)
            .unset(TaskRunEntity.Fields.AGENT_REF)
            .unset(TaskRunEntity.Fields.TIMEOUT_AT);
    TaskRunEntity preImage =
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, RunPhase.pending);
    }
    return preImage;
  }

  // Whether the run has any in-flight TaskRun: claimed/executing (phase queued/running) or
  // awaiting an external actor (status ready/waiting). Zero in-flight on an active run means the
  // graph advance was lost and must be recovered.
  public boolean existsInFlightByWorkflowRunRef(String workflowRunRef) {
    Query query =
        Query.query(
                Criteria.where(TaskRunEntity.Fields.WORKFLOW_RUN_REF)
                    .is(workflowRunRef)
                    .orOperator(
                        Criteria.where(TaskRunEntity.Fields.PHASE).in(RunPhase.queued, RunPhase.running),
                        Criteria.where(TaskRunEntity.Fields.STATUS).in(RunStatus.ready, RunStatus.waiting)))
            .maxTimeMsec(5000);
    return mongoTemplate.exists(query, TaskRunEntity.class);
  }

  // Return the page of waiting TaskRuns whose wait time has elapsed (sleep/lock parking).
  public List<TaskRunEntity> findWaitingDue(Date now, int limit) {
    Query query =
        Query.query(Criteria.where(TaskRunEntity.Fields.STATUS).is(RunStatus.waiting).and(TaskRunEntity.Fields.WAIT_UNTIL).lte(now))
            .with(Sort.by(Sort.Direction.ASC, TaskRunEntity.Fields.WAIT_UNTIL))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Claim a due waiting TaskRun for resume: clears waitUntil so a second instance's sweep skips
  // it. Returns whether this caller won (the winner resumes).
  public boolean tryStartWaitingResume(String id) {
    Query query =
        Query.query(
            Criteria.where("_id").is(id).and(TaskRunEntity.Fields.STATUS).is(RunStatus.waiting).and(TaskRunEntity.Fields.WAIT_UNTIL).lte(new Date()));
    return mongoTemplate
            .updateFirst(query, new Update().unset(TaskRunEntity.Fields.WAIT_UNTIL), TaskRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // A null observed seq fences on the run being unclaimed - a claim arriving between page and
  // Compare-And-Set carries a seq and fails the guard.
  private static void fence(Criteria criteria, Long observedClaimSeq) {
    if (observedClaimSeq != null) {
      criteria.and(TaskRunEntity.Fields.CLAIM_SEQ).is(observedClaimSeq);
    } else {
      criteria.and(TaskRunEntity.Fields.CLAIM_SEQ).exists(false);
    }
  }

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches, returning the pre-image (null = another caller won, so the caller does nothing).
  private TaskRunEntity findAndModifyPreImage(Query query, Update update) {
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
  }


  private void publish(TaskRunEntity preImage, RunStatus toStatus, RunPhase toPhase) {
    eventPublisher.publishEvent(
        new TaskRunTransition(
            preImage.getId(),
            preImage.getWorkflowRunRef(),
            preImage.getStatus(),
            preImage.getPhase(),
            toStatus,
            toPhase));
  }

  public ResponseEntity<TaskRun> get(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRun taskRun = new TaskRun(optTaskRunEntity.get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public Page<TaskRun> query(
      Optional<Date> from,
      Optional<Date> to,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), TaskRunEntity.Fields.CREATION_DATE));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where(TaskRunEntity.Fields.CREATION_DATE).gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where(TaskRunEntity.Fields.CREATION_DATE).lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where(TaskRunEntity.Fields.CREATION_DATE).gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO: centralize the checks in a common filter class
    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
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
        Criteria criteria = Criteria.where(TaskRunEntity.Fields.STATUS).in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryPhase.isPresent()) {
      if (queryPhase.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(RunPhase.class, q))) {
        Criteria criteria = Criteria.where(TaskRunEntity.Fields.PHASE).in(queryPhase.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "phase");
      }
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

    List<TaskRunEntity> taskRunEntities = mongoTemplate.find(query, TaskRunEntity.class);

    List<TaskRun> taskRuns = new LinkedList<>();
    taskRunEntities.forEach(e -> taskRuns.add(new TaskRun(e)));

    Page<TaskRun> pages = PageableExecutionUtils.getPage(taskRuns, pageable, () -> taskRuns.size());

    return pages;
  }

  public ResponseEntity<TaskRun> start(
      String taskRunId, Optional<TaskRunStartRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          taskRunEntity.setParams(
              ParameterUtil.addUniqueParams(
                  taskRunEntity.getParams(), optRunRequest.get().getParams()));
          if (!Objects.isNull(optRunRequest.get().getTimeout())
              && optRunRequest.get().getTimeout() != 0) {
            taskRunEntity.setTimeout(optRunRequest.get().getTimeout());
          }
          // Persist the request merge for the handler to re-read. The handler only acts on a
          // pending or queued TaskRun, so any other phase keeps its record untouched.
          if (RunPhase.pending.equals(taskRunEntity.getPhase())
              || RunPhase.queued.equals(taskRunEntity.getPhase())) {
            taskRunRepository.save(taskRunEntity);
          }
        }
        taskExecutionService.start(taskRunId);
        // Retrieve the refreshed state
        TaskRun taskRun = new TaskRun(taskRunRepository.findById(taskRunId).get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> end(String taskRunId, Optional<TaskRunEndRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          if (optRunRequest.get().getStatusMessage() != null
              && !optRunRequest.get().getStatusMessage().isEmpty()) {
            taskRunEntity.setStatusMessage(optRunRequest.get().getStatusMessage());
          }
          taskRunEntity.setResults(
              ResultUtil.addUniqueResults(
                  taskRunEntity.getResults(), optRunRequest.get().getResults()));
          if (optRunRequest.get().getStatus() == null) {
            taskRunEntity.setStatus(RunStatus.succeeded);
          } else if (!(RunStatus.failed.equals(optRunRequest.get().getStatus())
              || RunStatus.succeeded.equals(optRunRequest.get().getStatus())
              || RunStatus.invalid.equals(optRunRequest.get().getStatus()))) {
            throw new BoomerangException(BoomerangError.TASKRUN_INVALID_END_STATUS);
          } else {
            taskRunEntity.setStatus(optRunRequest.get().getStatus());
          }
        }
        // Persist the request merge for the handler to re-read. The handler ignores an end
        // request for a completed TaskRun, so a terminal record stays untouched.
        if (!RunPhase.completed.equals(taskRunEntity.getPhase())) {
          taskRunRepository.save(taskRunEntity);
        }
        taskExecutionService.end(taskRunId);
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> cancel(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        taskRunEntity.setStatus(RunStatus.cancelled);
        // Persist the cancelled status for the handler to re-read. The handler ignores an end
        // request for a completed TaskRun, so a terminal record stays untouched.
        if (!RunPhase.completed.equals(taskRunEntity.getPhase())) {
          taskRunRepository.save(taskRunEntity);
        }
        taskExecutionService.end(taskRunId);
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public StreamingResponseBody streamLog(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);

      // TODO sanitise and remove secure parameters
      //    List<String> removeList = buildRemovalList(taskId, taskExecution, activity);
      //    LOGGER.debug("Removal List Count: {} ", removeList.size());
      if (optTaskRunEntity.isPresent()) {
        return logClient.streamLog(
            optTaskRunEntity.get().getWorkflowRef(),
            optTaskRunEntity.get().getWorkflowRunRef(),
            optTaskRunEntity.get().getId());
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }
}
