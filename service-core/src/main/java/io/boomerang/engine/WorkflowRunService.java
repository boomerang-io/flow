package io.boomerang.engine;

import static java.util.stream.Collectors.groupingBy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.*;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.event.entity.EventInboxEntity;
import io.boomerang.event.enums.InboxStatus;
import io.boomerang.engine.model.*;
import io.boomerang.event.repository.EventInboxRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.workflow.ConvertUtil;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
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
import org.springframework.stereotype.Service;

@Service
public class WorkflowRunService {

  private static final Logger LOGGER = LogManager.getLogger();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final WorkflowRepository workflowRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final TaskRunService taskRunService;
  private final WorkflowExecutionService workflowExecutionService;
  private final TaskExecutionService taskExecutionService;
  private final EventInboxRepository eventInboxRepository;
  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  // Grace added on top of the timeout budget so a run at exactly its budget is not reaped.

  public WorkflowRunService(
      WorkflowRepository workflowRepository,
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      TaskRunService taskRunService,
      WorkflowExecutionService workflowExecutionService,
      @Lazy TaskExecutionService taskExecutionService,
      EventInboxRepository eventInboxRepository,
      MongoTemplate mongoTemplate,
      ApplicationEventPublisher eventPublisher) {
    this.workflowRepository = workflowRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.taskRunService = taskRunService;
    this.workflowExecutionService = workflowExecutionService;
    this.taskExecutionService = taskExecutionService;
    this.eventInboxRepository = eventInboxRepository;
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  // Return the page of ready/pending unclaimed WorkflowRuns for an agent to provision, oldest
  // first.
  public List<WorkflowRunEntity> findClaimableForProvision(int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.pending)
                    .and("claim.by")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaimForProvision transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public List<WorkflowRunEntity> findClaimableForTeardown(int limit) {
    // workspaces.0 exists = the run still has workspaces for the claimant to tear down.
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.completed)
                    .and("claim.by")
                    .exists(false)
                    .and("workspaces.0")
                    .exists(true))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaimForTeardown transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public WorkflowRunEntity tryClaimForProvision(String id, String claimedBy) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.ready)
                .and("phase")
                .is(RunPhase.pending)
                .and("claim.by")
                .exists(false));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("dispatcherRef", claimedBy)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setDispatcherRef(claimedBy);
    }
    return preImage;
  }

  public WorkflowRunEntity tryClaimForTeardown(String id, String claimedBy) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.completed)
                .and("claim.by")
                .exists(false)
                .and("workspaces.0")
                .exists(true));
    Update update =
        new Update()
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("dispatcherRef", claimedBy)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), preImage.getPhase());
      // Return the pre-image with the claim owner applied - teardown leaves the phase (completed)
      // unchanged, so only dispatcherRef needs patching for the caller's agent payload.
      preImage.setDispatcherRef(claimedBy);
    }
    return preImage;
  }

  public WorkflowRunEntity tryAdmit(String id, List<RunParam> resolvedParams) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.notstarted)
                .and("phase")
                .is(RunPhase.pending));
    Update update = new Update().set("status", RunStatus.ready).set("params", resolvedParams);
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

  public WorkflowRunEntity tryStart(String id, Date startTime, Long timeoutMinutes) {
    // Clearing the dispatch claim frees the completed-phase teardown claimable; claim.seq is
    // never cleared and survives.
    Query query =
        Query.query(
            Criteria.where("_id").is(id).and("phase").in(RunPhase.pending, RunPhase.queued));
    Update update =
        new Update()
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("startTime", startTime)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt");
    Date timeoutAt = RunTimeouts.deadline(startTime, timeoutMinutes);
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    WorkflowRunEntity preImage =
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
    if (preImage.getClaim() != null) {
      preImage.getClaim().setBy(null);
      preImage.getClaim().setAt(null);
      preImage.getClaim().setLeaseExpiresAt(null);
    }
    return preImage;
  }

  public WorkflowRunEntity tryComplete(
      String id, List<RunPhase> fromPhases, RunStatus status, String statusMessage, long duration) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").in(fromPhases));
    Update update =
        new Update()
            .set("status", status)
            .set("phase", RunPhase.completed)
            .set("duration", duration)
            .unset("timeoutAt");
    if (statusMessage != null) {
      update.set("statusMessage", statusMessage);
    }
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, status, RunPhase.completed);
    }
    return preImage;
  }

  public WorkflowRunEntity tryMarkTimedOut(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running));
    Update update = new Update().set("status", RunStatus.timedout);
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  public WorkflowRunEntity tryFinalize(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.completed));
    Update update = new Update().set("phase", RunPhase.finalized);
    WorkflowRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.finalized);
    }
    return preImage;
  }

  // Pause Compare-And-Set: a running, not-yet-paused run gains the flag. Returns whether this
  // caller won (the pre-image is not needed - pause publishes no transition).
  public boolean tryPause(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.running)
                .and("pauseRequestedAt")
                .exists(false));
    return mongoTemplate
            .updateFirst(query, new Update().set("pauseRequestedAt", new Date()), WorkflowRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // Resume Compare-And-Set: clears the pause flag. Returns whether this caller won.
  public boolean tryResume(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("pauseRequestedAt").exists(true));
    return mongoTemplate
            .updateFirst(query, new Update().unset("pauseRequestedAt"), WorkflowRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // Paused runs are excluded from both recovery sweeps. The deadline deliberately does not
  // advance while paused - a run paused past its deadline is reaped on resume.
  public List<WorkflowRunEntity> findTimedOut(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("timeoutAt")
                    .lte(now)
                    .and("phase")
                    .is(RunPhase.running)
                    .and("pauseRequestedAt")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "timeoutAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public List<WorkflowRunEntity> findRunningStartedBefore(Date startedBefore, int limit) {
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.running)
                    .and("startTime")
                    .lte(startedBefore)
                    .and("pauseRequestedAt")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "startTime"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public List<WorkflowRunEntity> findFinalizableWithoutWorkspaces(int limit) {
    Query query =
        Query.query(
                Criteria.where("phase").is(RunPhase.completed).and("workspaces.0").exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public void setAwaitingApproval(String id, boolean awaitingApproval) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("isAwaitingApproval", awaitingApproval),
        WorkflowRunEntity.class);
  }

  public void appendResult(String id, RunResult result) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().push("results", result),
        WorkflowRunEntity.class);
  }

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches, returning the pre-image (null = another caller won, so the caller does nothing).
  private WorkflowRunEntity findAndModifyPreImage(Query query, Update update) {
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
  }

  private void publish(WorkflowRunEntity preImage, RunStatus toStatus, RunPhase toPhase) {
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            preImage.getId(),
            preImage.getWorkflowRef(),
            preImage.getStatus(),
            preImage.getPhase(),
            toStatus,
            toPhase));
  }

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

    Page<WorkflowRun> pages = PageableExecutionUtils.getPage(wfRuns, pageable, () -> wfRuns.size());

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
    if (!tryPause(workflowRunId)) {
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
    if (tryResume(workflowRunId)) {
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
   * To be used internally within the Engine
   */
  protected WorkflowRun timeout(String workflowRunId, boolean taskRunTimeout) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    // Compare-And-Set precondition: only a running run can be marked timed out - a late timeout
    // can never overwrite a terminal status. Only the winner drives the timeout to completion.
    WorkflowRunEntity preImage = tryMarkTimedOut(workflowRunId);
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
      // Lineage on typed fields: initiatedByRef points at the first origin (preserved across
      // chained retries), trigger marks this run as a retry.
      if (!TriggerEnum.retry.getTrigger().equals(wfRunEntity.getTrigger())) {
        wfRunEntity.setInitiatedByRef(workflowRunId);
        wfRunEntity.setTrigger(TriggerEnum.retry.getTrigger());
      }
      workflowRunRepository.save(wfRunEntity);

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
    wfRun.getAnnotations().remove("boomerang.io/team-params");
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
              tr.getAnnotations().put("boomerang.io/status", request.getStatus());
              tr.setPreApproved(true);
              tr.getResults().addAll(request.getResults());
              taskRunRepository.save(tr);
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
      String payload = OBJECT_MAPPER.writeValueAsString(request);
      LOGGER.info("Received Request Payload: ");
      LOGGER.info(payload);
    } catch (JacksonException e) {
      LOGGER.error(e.getStackTrace());
    }
  }
}
