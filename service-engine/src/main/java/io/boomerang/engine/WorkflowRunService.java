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
import io.boomerang.common.enums.TimeoutCause;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.*;
import io.boomerang.engine.entity.EventInboxEntity;
import io.boomerang.engine.enums.InboxStatus;
import io.boomerang.engine.model.*;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.EventInboxRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRepository;
import io.boomerang.engine.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.util.ConvertUtil;
import io.boomerang.util.ParameterUtil;
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
import org.springframework.stereotype.Service;

@Service
public class WorkflowRunService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final TaskRunRepository taskRunRepository;
  private final TaskRunService taskRunService;
  private final ActionRepository actionRepository;
  private final WorkflowExecutionService workflowExecutionService;
  private final TaskExecutionService taskExecutionService;
  private final EventInboxRepository eventInboxRepository;
  private final MongoTemplate mongoTemplate;

  public WorkflowRunService(
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      WorkflowRunRepository workflowRunRepository,
      TaskRunRepository taskRunRepository,
      TaskRunService taskRunService,
      ActionRepository actionRepository,
      WorkflowExecutionService workflowExecutionService,
      @Lazy TaskExecutionService taskExecutionService,
      EventInboxRepository eventInboxRepository,
      MongoTemplate mongoTemplate) {
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.taskRunRepository = taskRunRepository;
    this.taskRunService = taskRunService;
    this.actionRepository = actionRepository;
    this.workflowExecutionService = workflowExecutionService;
    this.taskExecutionService = taskExecutionService;
    this.eventInboxRepository = eventInboxRepository;
    this.mongoTemplate = mongoTemplate;
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
        wfRunEntity.getWorkspaces().addAll(optRunRequest.get().getWorkspaces());
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
    if (workflowRunRepository.tryPause(workflowRunId) == null) {
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
    // Resume = clear the flag + reconcile: the advance re-drives whatever the pause held back.
    if (workflowRunRepository.tryResume(workflowRunId) != null) {
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
    if (workflowRunRepository.tryMarkTimedOut(
            workflowRunId, taskRunTimeout ? TimeoutCause.task : TimeoutCause.workflow)
        != null) {
      workflowExecutionService.timeout(workflowRunId);
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
      wfRunEntity.setTimeoutCause(null);
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

  public WorkflowRun retryFromTask(String workflowRunId, String taskRunId) {
    if (workflowRunId == null || workflowRunId.isBlank() || taskRunId == null || taskRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    WorkflowRunEntity wfRunEntity =
        workflowRunRepository
            .findById(workflowRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    // Retire the step's downstream closure and re-create it live.
    taskExecutionService.supersedeFrom(taskRunId);
    // A finished run returns to running so the fresh generation can execute.
    if (RunPhase.completed.equals(wfRunEntity.getPhase())
        || RunPhase.finalized.equals(wfRunEntity.getPhase())) {
      workflowRunRepository.tryReopen(workflowRunId);
    }
    taskExecutionService.advance(workflowRunId);
    return ConvertUtil.entityToModel(
        workflowRunRepository
            .findById(workflowRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF)),
        WorkflowRun.class);
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
   * Deletes the WorkflowRun and associated TaskRuns
   */
  public void delete(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    actionRepository.deleteByWorkflowRunRef(workflowRunId);
    taskRunRepository.deleteByWorkflowRunRef(workflowRunId);
    workflowRunRepository.deleteById(workflowRunId);
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
    List<TaskRun> taskRuns = getTaskRuns(workflowRunId);
    // Set preApproved or call endTaskRun for each with the status.
    List<TaskRun> topicTaskRuns =
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
    // Live generation only - default responses never show superseded generations.
    List<TaskRunEntity> taskRunEntities =
        taskRunRepository.findByWorkflowRunRefAndSupersededAtIsNull(workflowRunId);
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
      ObjectMapper objectMapper = new ObjectMapper();
      String payload = objectMapper.writeValueAsString(request);
      LOGGER.info("Received Request Payload: ");
      LOGGER.info(payload);
    } catch (JacksonException e) {
      LOGGER.error(e.getStackTrace());
    }
  }
}
