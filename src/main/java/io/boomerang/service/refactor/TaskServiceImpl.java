package io.boomerang.service.refactor;

import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.github.alturkovic.lock.Lock;
import com.github.alturkovic.lock.exception.LockNotAvailableException;

import io.boomerang.model.ApprovalStatus;
import io.boomerang.model.RequestFlowExecution;
import io.boomerang.model.Task;
import io.boomerang.model.WorkflowSchedule;
import io.boomerang.mongo.entity.ActivityEntity;
import io.boomerang.mongo.entity.ApprovalEntity;
import io.boomerang.mongo.entity.FlowTaskTemplateEntity;
import io.boomerang.mongo.entity.RevisionEntity;
import io.boomerang.mongo.entity.TaskExecutionEntity;
import io.boomerang.mongo.entity.WorkflowEntity;
import io.boomerang.mongo.model.Dag;
import io.boomerang.mongo.model.ErrorResponse;
import io.boomerang.mongo.model.KeyValuePair;
import io.boomerang.mongo.model.ManualType;
import io.boomerang.mongo.model.Revision;
import io.boomerang.mongo.model.TaskStatus;
import io.boomerang.mongo.model.TaskType;
import io.boomerang.mongo.model.WorkflowScheduleType;
import io.boomerang.mongo.model.internal.InternalTaskRequest;
import io.boomerang.mongo.model.internal.InternalTaskResponse;
import io.boomerang.mongo.model.next.DAGTask;
import io.boomerang.mongo.model.next.Dependency;
import io.boomerang.mongo.service.ActivityTaskService;
import io.boomerang.mongo.service.ApprovalService;
import io.boomerang.mongo.service.FlowSettingsService;
import io.boomerang.mongo.service.FlowTaskTemplateService;
import io.boomerang.mongo.service.FlowWorkflowActivityService;
import io.boomerang.mongo.service.FlowWorkflowService;
import io.boomerang.mongo.service.RevisionService;
import io.boomerang.service.PropertyManager;
import io.boomerang.service.crud.FlowActivityService;
import io.boomerang.service.crud.WorkflowScheduleService;
import io.boomerang.service.runner.misc.ControllerClient;

@Service
public class TaskServiceImpl implements TaskService {

  @Autowired
  @Lazy
  private ControllerClient controllerClient;

  @Autowired
  private FlowWorkflowActivityService activityService;

  @Autowired
  private FlowWorkflowService workflowService;

  @Autowired
  private FlowTaskTemplateService templateService;

  @Autowired
  private RevisionService workflowVersionService;

  @Autowired
  private ActivityTaskService taskActivityService;

  @Autowired
  private DAGUtility dagUtility;

  @Autowired
  private Lock lock;

  @Autowired
  private ApprovalService approvalService;

  @Autowired
  private PropertyManager propertyManager;

  @Autowired
  private LockManager lockManager;
  
  @Autowired
  private FlowSettingsService flowSettingsService;

  private static final Logger LOGGER = LogManager.getLogger();

  @Autowired
  private TaskClient flowClient;

  @Autowired
  private FlowActivityService flowActivityService;

  @Autowired
  private WorkflowScheduleService scheduleService;


  @Override
  @Async("flowAsyncExecutor")
  public void createTask(InternalTaskRequest request) {

    String taskId = request.getActivityId();
    LOGGER.debug("[{}] Recieved creating task request", taskId);

    TaskExecutionEntity taskExecution = taskActivityService.findById(taskId);
    ActivityEntity activity =
        activityService.findWorkflowActivtyById(taskExecution.getActivityId());

    if (activity.getStatus() == TaskStatus.cancelled) {
      LOGGER.error("[{}] Workflow has been marked as cancelled, not starting task.",
          activity.getId());
      return;
    }

    WorkflowEntity workflow = workflowService.getWorkflow(activity.getWorkflowId());
    String workflowName = workflow.getName();

    Task task = getTask(taskExecution);

    if (task == null || taskExecution.getFlowTaskStatus() != TaskStatus.notstarted) {
      LOGGER.debug("Task is null or hasn't started yet");
      return;
    }

    TaskType taskType = task.getTaskType();
    taskExecution.setStartTime(new Date());
    taskExecution.setFlowTaskStatus(TaskStatus.inProgress);
    taskExecution = taskActivityService.save(taskExecution);

    boolean canRunTask = dagUtility.canCompleteTask(activity, task.getTaskId());

    String activityId = activity.getId();

    LOGGER.debug("[{}] Examining task type: {}", taskId, taskType);

    if (canRunTask) {
      LOGGER.debug("[{}] Can run task? {}", taskId, canRunTask);

      if (taskType == TaskType.decision) {
        InternalTaskResponse response = new InternalTaskResponse();
        response.setActivityId(taskExecution.getId());
        response.setStatus(TaskStatus.completed);
        processDecision(task, activity.getId());
        this.endTask(response);
      } else if (taskType == TaskType.template || taskType == TaskType.script) {
        List<KeyValuePair> labels = workflow.getLabels();
        controllerClient.submitTemplateTask(this, flowClient, task, activityId, workflowName,
            labels);
      } else if (taskType == TaskType.customtask) {
        List<KeyValuePair> labels = workflow.getLabels();
        controllerClient.submitCustomTask(this, flowClient, task, activityId, workflowName, labels);
      } else if (taskType == TaskType.acquirelock) {
        createLock(task, activity);
      } else if (taskType == TaskType.releaselock) {
        releaseLock(task, activity);
      } else if (taskType == TaskType.runworkflow) {
        this.runWorkflow(task, activity);
      } else if (taskType == TaskType.runscheduledworkflow) {
        this.runScheduledWorkflow(task, activity, workflowName);
      } else if (taskType == TaskType.setwfstatus) {
        saveWorkflowStatus(task, activity);
        InternalTaskResponse response = new InternalTaskResponse();
        response.setActivityId(taskExecution.getId());
        response.setStatus(TaskStatus.completed);
        this.endTask(response);
      } else if (taskType == TaskType.setwfproperty) {
        saveWorkflowProperty(task, activity, taskExecution);
        InternalTaskResponse response = new InternalTaskResponse();
        response.setActivityId(taskExecution.getId());
        response.setStatus(TaskStatus.completed);
        this.endTask(response);
      } else if (taskType == TaskType.approval) {
        createApprovalNotification(taskExecution, task, activity, workflow, ManualType.approval);
      } else if (taskType == TaskType.manual) {
        createApprovalNotification(taskExecution, task, activity, workflow, ManualType.task);
      } else if (taskType == TaskType.eventwait) {
        createWaitForEventTask(taskExecution);
      }
    } else {
      LOGGER.debug("[{}] Skipping task", taskId);
      InternalTaskResponse response = new InternalTaskResponse();
      response.setStatus(TaskStatus.skipped);
      response.setActivityId(taskExecution.getId());

      endTask(response);
    }
  }

  private void saveWorkflowStatus(Task task, ActivityEntity activity) {
    String status = task.getInputs().get("status");
    if (!status.isBlank()) {
      TaskStatus taskStatus = TaskStatus.valueOf(status);
      activity.setStatusOverride(taskStatus);
      this.activityService.saveWorkflowActivity(activity);
    }
  }

  private void processDecision(Task task, String activityId) {
    String decisionValue = task.getDecisionValue();
    ControllerRequestProperties properties =
        propertyManager.buildRequestPropertyLayering(task, activityId, task.getWorkflowId());
    String value = decisionValue;
    value = propertyManager.replaceValueWithProperty(value, activityId, properties);
    TaskExecutionEntity taskExecution = taskActivityService.findById(task.getTaskActivityId());
    taskExecution.setSwitchValue(value);
    taskActivityService.save(taskExecution);
  }

  private void releaseLock(Task task, ActivityEntity activity) {

    LOGGER.debug("[{}] Releasing lock: ", task.getTaskActivityId());

    lockManager.releaseLock(task, activity.getId());
    InternalTaskResponse response = new InternalTaskResponse();
    response.setActivityId(task.getTaskActivityId());
    response.setStatus(TaskStatus.completed);
    this.endTask(response);
  }

  private void runWorkflow(Task task, ActivityEntity activity) {

    if (task.getInputs() != null) {
      RequestFlowExecution request = new RequestFlowExecution();
      request.setWorkflowId(task.getInputs().get("workflowId"));
      Map<String, String> properties = new HashMap<>();
      for (Map.Entry<String, String> entry : task.getInputs().entrySet()) {
        if (!"workflowId".equals(entry.getKey())) {
          properties.put(entry.getKey(), entry.getValue());
        }
      }

      request.setProperties(properties);
      String workflowActivityId = flowClient.submitWebhookEvent(request);
      if (workflowActivityId != null) {
        TaskExecutionEntity taskExecution = taskActivityService.findById(task.getTaskActivityId());
        taskExecution.setRunWorkflowActivityId(workflowActivityId);
        taskExecution.setRunWorkflowId(request.getWorkflowId());
        taskActivityService.save(taskExecution);
      }
    }

    InternalTaskResponse response = new InternalTaskResponse();
    response.setActivityId(task.getTaskActivityId());
    response.setStatus(TaskStatus.completed);
    this.endTask(response);
  }

  private void runScheduledWorkflow(Task task, ActivityEntity activity, String workflowName) {
    InternalTaskResponse response = new InternalTaskResponse();
    response.setActivityId(task.getTaskActivityId());
    response.setStatus(TaskStatus.failure);

    if (task.getInputs() != null) {
      String workflowId = task.getInputs().get("workflowId");
      Integer futureIn = Integer.valueOf(task.getInputs().get("futureIn"));
      String futurePeriod = task.getInputs().get("futurePeriod");
      Date executionDate = activity.getCreationDate();
      String timezone = task.getInputs().get("timezone");
      LOGGER.debug("*******Run Scheduled Workflow System Task******");
      LOGGER.debug("Scheduling new task in " + futureIn + " " + futurePeriod);

      if (futureIn != null && futureIn != 0 && StringUtils.indexOfAny(futurePeriod,
          new String[] {"minutes", "hours", "days", "weeks", "months"}) >= 0) {
        Calendar executionCal = Calendar.getInstance();
        executionCal.setTime(executionDate);
        Integer calField = Calendar.MINUTE;
        switch (futurePeriod) {
          case "hours":
            calField = Calendar.HOUR;
            break;
          case "days":
            calField = Calendar.DATE;
            break;
          case "weeks":
            futureIn = futureIn * 7;
            calField = Calendar.DATE;
            break;
          case "months":
            calField = Calendar.MONTH;
            break;
        }
        executionCal.add(calField, futureIn);
        if (!futurePeriod.equals("minutes") && !futurePeriod.equals("hours")) {
          String[] hoursTime = task.getInputs().get("time").split(":");
          Integer hours = Integer.valueOf(hoursTime[0]);
          Integer minutes = Integer.valueOf(hoursTime[1]);
          LOGGER
              .debug("With time to be set to: " + task.getInputs().get("time") + " in " + timezone);
          executionCal.setTimeZone(TimeZone.getTimeZone(timezone));
          executionCal.set(Calendar.HOUR, hours);
          executionCal.set(Calendar.MINUTE, minutes);
          LOGGER.debug(
              "With execution set to: " + executionCal.getTime().toString() + " in " + timezone);
          executionCal.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
        LOGGER.debug("With execution set to: " + executionCal.getTime().toString() + " in UTC");

        // Define new properties removing the System Task specific properties
        ControllerRequestProperties requestProperties = propertyManager
            .buildRequestPropertyLayering(task, activity.getId(), activity.getWorkflowId());

        Map<String, String> properties = new HashMap<>();
        for (Map.Entry<String, String> entry : task.getInputs().entrySet()) {
          if (!"workflowId".equals(entry.getKey()) && !"futureIn".equals(entry.getKey())
              && !"futurePeriod".equals(entry.getKey()) && !"time".equals(entry.getKey())
              && !"timezone".equals(entry.getKey())) {
            String value = entry.getValue();
            if (value != null) {
              value = propertyManager.replaceValueWithProperty(value, activity.getId(),
                  requestProperties);
            }
            properties.put(entry.getKey(), value);
          }
        }

        // Define and create the schedule
        WorkflowSchedule schedule = new WorkflowSchedule();
        schedule.setWorkflowId(workflowId);
        schedule.setName(task.getTaskName());
        schedule
            .setDescription("This schedule was generated through a Run Scheduled Workflow task.");
        schedule.setParametersMap(properties);
        schedule.setCreationDate(activity.getCreationDate());
        schedule.setDateSchedule(executionCal.getTime());
        schedule.setTimezone(timezone);
        schedule.setType(WorkflowScheduleType.runOnce);
        List<KeyValuePair> labels = new LinkedList<>();
        labels.add(new KeyValuePair("workflowName", workflowName));
        schedule.setLabels(labels);
        WorkflowSchedule workflowSchedule = scheduleService.createSchedule(schedule);
        if (workflowSchedule != null && workflowSchedule.getId() != null) {
          LOGGER.debug("Workflow Scheudle (" + workflowSchedule.getId() + ") created.");
          // TODO: Add a taskExecution with the ScheduleId so it can be deep linked.
          response.setStatus(TaskStatus.completed);
        }
      }
    }

    this.endTask(response);
  }

  private void createLock(Task task, ActivityEntity activity) {

    LOGGER.debug("[{}] Creating lock: ", task.getTaskActivityId());

    InternalTaskResponse response = new InternalTaskResponse();
    response.setActivityId(task.getTaskActivityId());
    try {
    	lockManager.acquireLock(task, activity.getId());
      response.setStatus(TaskStatus.completed);
    }
    catch (LockNotAvailableException e) {
      response.setStatus(TaskStatus.failure);
    }
    this.endTask(response);
  }

  private void createWaitForEventTask(TaskExecutionEntity taskExecution) {

    LOGGER.debug("[{}] Creating wait for event task", taskExecution.getActivityId());

    if (taskExecution.isPreApproved()) {
      InternalTaskResponse response = new InternalTaskResponse();
      response.setActivityId(taskExecution.getId());
      response.setStatus(TaskStatus.completed);
      this.endTask(response);
    } else {
      taskExecution.setFlowTaskStatus(TaskStatus.waiting);
      taskActivityService.save(taskExecution);
    }
  }

  private void createApprovalNotification(TaskExecutionEntity taskExecution, Task task,
      ActivityEntity activity, WorkflowEntity workflow, ManualType type) {
    taskExecution.setFlowTaskStatus(TaskStatus.waiting);
    taskExecution = taskActivityService.save(taskExecution);
    ApprovalEntity approval = new ApprovalEntity();
    approval.setTaskActivityId(taskExecution.getId());
    approval.setActivityId(activity.getId());
    approval.setWorkflowId(workflow.getId());
    approval.setTeamId(workflow.getFlowTeamId());
    approval.setStatus(ApprovalStatus.submitted);
    approval.setType(type);
    approval.setCreationDate(new Date());
    approval.setNumberOfApprovers(1);

    if (ManualType.approval == type) {
      if (task.getInputs() != null) {
        String approverGroupId = task.getInputs().get("approverGroupId");
        String numberOfApprovers = task.getInputs().get("numberOfApprovers");

        if (approverGroupId != null && !approverGroupId.isBlank()) {
          approval.setApproverGroupId(approverGroupId);
        }
        if (numberOfApprovers != null && !numberOfApprovers.isBlank()) {
          approval.setNumberOfApprovers(Integer.valueOf(numberOfApprovers));
        }
      }
    }
    approvalService.save(approval);
    activity.setAwaitingApproval(true);
    this.activityService.saveWorkflowActivity(activity);
  }

  private void saveWorkflowProperty(Task task, ActivityEntity activity,
      TaskExecutionEntity taskEntity) {
    if (taskEntity.getOutputProperties() == null) {
      taskEntity.setOutputProperties(new LinkedList<>());
    }

    String input = task.getInputs().get("value");
    String output = task.getInputs().get("output");

    List<KeyValuePair> outputProperties = taskEntity.getOutputProperties();

    KeyValuePair outputProperty = new KeyValuePair();
    outputProperty.setKey(output);

    ControllerRequestProperties requestProperties = propertyManager
        .buildRequestPropertyLayering(task, activity.getId(), activity.getWorkflowId());
    String outputValue =
        propertyManager.replaceValueWithProperty(input, activity.getId(), requestProperties);

    outputProperty.setValue(outputValue);
    outputProperties.add(outputProperty);
    taskEntity.setOutputProperties(outputProperties);
    taskActivityService.save(taskEntity);

  }

  @Override
  @Async("flowAsyncExecutor")
  public void endTask(InternalTaskResponse request) {

    String activityId = request.getActivityId();
    LOGGER.info("[{}] Recieved end task request", activityId);
    TaskExecutionEntity activity = taskActivityService.findById(activityId);

    ActivityEntity workflowActivity =
        this.activityService.findWorkflowActivtyById(activity.getActivityId());

    if (workflowActivity.getStatus() == TaskStatus.cancelled) {
      LOGGER.error("[{}] Workflow has been marked as cancelled, not ending task", activityId);
      activity.setFlowTaskStatus(TaskStatus.cancelled);
      long duration = new Date().getTime() - activity.getStartTime().getTime();
      activity.setDuration(duration);
      taskActivityService.save(activity);
      return;
    }

    RevisionEntity revision =
        workflowVersionService.getWorkflowlWithId(workflowActivity.getWorkflowRevisionid());
    Task currentTask = getTask(activity);
    List<Task> tasks = this.createTaskList(revision, workflowActivity);

    String storeId = workflowActivity.getId();

    List<String> keys = new LinkedList<>();
    keys.add(storeId);

    workflowActivity = this.activityService.findWorkflowActivtyById(activity.getActivityId());


    activity.setFlowTaskStatus(request.getStatus());
    long duration = new Date().getTime() - activity.getStartTime().getTime();
    activity.setDuration(duration);


    if (request.getOutputProperties() != null && !request.getOutputProperties().isEmpty()) {
      activity.setOutputs(request.getOutputProperties());
    }

    activity = taskActivityService.save(activity);

    boolean finishedAll = this.finishedAll(workflowActivity, tasks, currentTask);

    LOGGER.debug("[{}] Finished all previous tasks? {}", activityId, finishedAll);


    LOGGER.debug("[{}] Attempting to get lock", activityId);
    String tokenId = getLock(storeId, keys, 105000);
    LOGGER.debug("[{}] Obtained lock", activityId);

    workflowActivity = this.activityService.findWorkflowActivtyById(activity.getActivityId());
    updatePendingAprovalStatus(workflowActivity);

    activity.setFlowTaskStatus(request.getStatus());

    String workflowActivityId = workflowActivity.getId();

    boolean workflowQuotasEnabled = flowSettingsService.getConfiguration("features", "workflowQuotas").getBooleanValue();
    LOGGER.debug("[{}] Workflow Quotas Enabled", workflowQuotasEnabled);
    
    if (workflowQuotasEnabled && this.flowActivityService.hasExceededExecutionQuotas(workflowActivityId)) {
      LOGGER.error("Workflow has been cancelled due to its max workflow duration has exceeded.");
      ErrorResponse response = new ErrorResponse();
      response
          .setMessage("Workflow execution terminated due to exceeding maxinum workflow duration.");
      response.setCode("001");

      this.flowActivityService.cancelWorkflowActivity(workflowActivityId, response);
    } else {
      executeNextStep(workflowActivity, tasks, currentTask, finishedAll);
    }
    lock.release(keys, "locks", tokenId);
    LOGGER.debug("[{}] Released lock", activityId);
  }

  private void updatePendingAprovalStatus(ActivityEntity workflowActivity) {
    long count = approvalService.getApprovalCountForActivity(workflowActivity.getId(),
        ApprovalStatus.submitted);
    boolean existingApprovals = (count > 0);
    workflowActivity.setAwaitingApproval(existingApprovals);
    this.activityService.saveWorkflowActivity(workflowActivity);
  }

  private String getLock(String storeId, List<String> keys, long timeout) {
    RetryTemplate retryTemplate = getRetryTemplate();
    return retryTemplate.execute(ctx -> {
      final String token = lock.acquire(keys, "locks", timeout);
      if (StringUtils.isEmpty(token)) {
        throw new LockNotAvailableException(
            String.format("Lock not available for keys: %s in store %s", keys, storeId));
      }
      return token;
    });
  }

  private RetryTemplate getRetryTemplate() {
    RetryTemplate retryTemplate = new RetryTemplate();
    FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(2000l);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(100);
    retryTemplate.setRetryPolicy(retryPolicy);
    return retryTemplate;
  }

  private void finishWorkflow(ActivityEntity activity) {

    WorkflowEntity workflow = workflowService.getWorkflow(activity.getWorkflowId());

    this.controllerClient.terminateFlow(workflow.getId(), workflow.getName(), activity.getId());
    boolean workflowCompleted = dagUtility.validateWorkflow(activity);

    if (activity.getStatusOverride() != null) {
      activity.setStatus(activity.getStatusOverride());
    } else {
      if (workflowCompleted) {
        activity.setStatus(TaskStatus.completed);
      } else {
        activity.setStatus(TaskStatus.failure);
      }
    }

    final Date finishDate = new Date();
    final long duration = finishDate.getTime() - activity.getCreationDate().getTime();
    activity.setDuration(duration);

    List<TaskExecutionEntity> taskExecutions =
        taskActivityService.findTaskActiivtyForActivity(activity.getId());
    if (activity.getOutputProperties() == null) {
      activity.setOutputProperties(new LinkedList<>());
    }

    for (TaskExecutionEntity taskExecution : taskExecutions) {
      if (taskExecution.getOutputProperties() != null) {
        activity.getOutputProperties().addAll(taskExecution.getOutputProperties());
      }
    }

    this.activityService.saveWorkflowActivity(activity);

  }

  private void executeNextStep(ActivityEntity workflowActivity, List<Task> tasks, Task currentTask,
      boolean finishedAll) {
    LOGGER.debug("[{}] Looking at next tasks", workflowActivity.getId());
    LOGGER.debug("Testing at next tasks");
    List<Task> nextNodes = this.getTasksDependants(tasks, currentTask);
    LOGGER.debug("Testing at next tasks: {}", nextNodes.size());

    for (Task next : nextNodes) {

      if (next.getTaskType() == TaskType.end) {
        if (finishedAll) {
          LOGGER.debug("FINISHED ALL");
          this.finishWorkflow(workflowActivity);
          return;
        }
        continue;
      }

      boolean executeTask = canExecuteTask(workflowActivity, next);
      LOGGER.debug("[{}] Task: {}", workflowActivity.getId(), next.getTaskName());


      if (executeTask) {
        TaskExecutionEntity task = this.taskActivityService
            .findByTaskIdAndActivityId(next.getTaskId(), workflowActivity.getId());
        if (task == null) {
          LOGGER.debug("Reached node which should not be executed.");
        } else {
          InternalTaskRequest taskRequest = new InternalTaskRequest();
          taskRequest.setActivityId(task.getId());
          flowClient.startTask(this, taskRequest);
        }
      }
    }
  }

  private boolean finishedAll(ActivityEntity workflowActivity, List<Task> tasks, Task currentTask) {
    boolean finishedAll = true;

    List<Task> nextNodes = this.getTasksDependants(tasks, currentTask);
    for (Task next : nextNodes) {
      if (next.getTaskType() == TaskType.end) {
        List<String> deps = next.getDependencies();
        for (String dep : deps) {
          TaskExecutionEntity task =
              this.taskActivityService.findByTaskIdAndActivityId(dep, workflowActivity.getId());
          if (task == null) {
            continue;
          }

          TaskStatus status = task.getFlowTaskStatus();
          if (status == TaskStatus.inProgress || status == TaskStatus.notstarted
              || status == TaskStatus.waiting) {
            finishedAll = false;
          }
        }
      }
    }

    return finishedAll;
  }

  private boolean canExecuteTask(ActivityEntity workflowActivity, Task next) {
    List<String> deps = next.getDependencies();
    for (String dep : deps) {
      TaskExecutionEntity task =
          taskActivityService.findByTaskIdAndActivityId(dep, workflowActivity.getId());
      if (task != null) {
        TaskStatus status = task.getFlowTaskStatus();
        if (status == TaskStatus.inProgress || status == TaskStatus.notstarted
            || status == TaskStatus.waiting) {
          return false;
        }
      }
    }
    return true;
  }

  private List<Task> getTasksDependants(List<Task> tasks, Task currentTask) {
    return tasks.stream().filter(c -> c.getDependencies().contains(currentTask.getTaskId()))
        .collect(Collectors.toList());
  }

  private Task getTask(TaskExecutionEntity taskActivity) {
    ActivityEntity activity = activityService.findWorkflowActivtyById(taskActivity.getActivityId());
    RevisionEntity revision =
        workflowVersionService.getWorkflowlWithId(activity.getWorkflowRevisionid());
    List<Task> tasks = createTaskList(revision, activity);
    String taskId = taskActivity.getTaskId();
    return tasks.stream().filter(tsk -> taskId.equals(tsk.getTaskId())).findAny().orElse(null);
  }

  private List<Task> createTaskList(RevisionEntity revisionEntity, ActivityEntity activity) { // NOSONAR
    final Dag dag = revisionEntity.getDag();
    final List<Task> taskList = new LinkedList<>();
    for (final DAGTask dagTask : dag.getTasks()) {

      final Task newTask = new Task();
      newTask.setTaskId(dagTask.getTaskId());
      newTask.setTaskType(dagTask.getType());
      newTask.setTaskName(dagTask.getLabel());

      final String workFlowId = revisionEntity.getWorkFlowId();
      newTask.setWorkflowId(workFlowId);

      if (dagTask.getType() == TaskType.script || dagTask.getType() == TaskType.template
          || dagTask.getType() == TaskType.customtask) {

        TaskExecutionEntity task =
            taskActivityService.findByTaskIdAndActivityId(dagTask.getTaskId(), activity.getId());
        if (task != null) {
          newTask.setTaskActivityId(task.getId());
        }

        String templateId = dagTask.getTemplateId();
        final FlowTaskTemplateEntity flowTaskTemplate =
            templateService.getTaskTemplateWithId(templateId);
        newTask.setTemplateId(flowTaskTemplate.getId());


        Integer templateVersion = dagTask.getTemplateVersion();
        List<Revision> revisions = flowTaskTemplate.getRevisions();
        if (revisions != null) {
          Optional<Revision> result = revisions.stream().parallel()
              .filter(revision -> revision.getVersion().equals(templateVersion)).findAny();
          if (result.isPresent()) {
            Revision revision = result.get();
            newTask.setRevision(revision);
            newTask.setResults(revision.getResults());
          } else {
            Optional<Revision> latestRevision = revisions.stream()
                .sorted(Comparator.comparingInt(Revision::getVersion).reversed()).findFirst();
            if (latestRevision.isPresent()) {
              newTask.setRevision(latestRevision.get());
              newTask.setResults(newTask.getRevision().getResults());
            }
          }
        } else {
          throw new IllegalArgumentException("Invalid task template selected: " + templateId);
        }

        Map<String, String> properties = new HashMap<>();
        if (dagTask.getProperties() != null) {
          for (KeyValuePair property : dagTask.getProperties()) {
            properties.put(property.getKey(), property.getValue());
          }
        }
        newTask.setInputs(properties);
        if (newTask.getResults() == null || newTask.getResults().size() == 0) {
          newTask.setResults(dagTask.getResults());
        }
      } else if (dagTask.getType() == TaskType.decision) {
        TaskExecutionEntity task =
            taskActivityService.findByTaskIdAndActivityId(dagTask.getTaskId(), activity.getId());
        if (task != null) {
          newTask.setTaskActivityId(task.getId());
        }


        newTask.setDecisionValue(dagTask.getDecisionValue());
      } else if (dagTask.getType() == TaskType.manual || dagTask.getType() == TaskType.runworkflow
          || dagTask.getType() == TaskType.runscheduledworkflow
          || dagTask.getType() == TaskType.setwfproperty
          || dagTask.getType() == TaskType.setwfstatus || dagTask.getType() == TaskType.acquirelock
          || dagTask.getType() == TaskType.releaselock) {

        TaskExecutionEntity task =
            taskActivityService.findByTaskIdAndActivityId(dagTask.getTaskId(), activity.getId());
        if (task != null) {
          newTask.setTaskActivityId(task.getId());
        }

        Map<String, String> properties = new HashMap<>();
        if (dagTask.getProperties() != null) {
          for (KeyValuePair property : dagTask.getProperties()) {
            properties.put(property.getKey(), property.getValue());
          }
        }
        newTask.setInputs(properties);
      }

      final List<String> taskDepedancies = new LinkedList<>();
      for (Dependency dependency : dagTask.getDependencies()) {
        taskDepedancies.add(dependency.getTaskId());
      }
      newTask.setDetailedDepednacies(dagTask.getDependencies());
      newTask.setDependencies(taskDepedancies);
      taskList.add(newTask);
    }
    return taskList;
  }

  @Override
  public List<String> updateTaskActivityForTopic(String activityId, String topic) {

    List<String> ids = new LinkedList<>();

    LOGGER.info("[{}] Fidning task actiivty id based on topic.", activityId);
    ActivityEntity activity = activityService.findWorkflowActivtyById(activityId);
    RevisionEntity revision =
        workflowVersionService.getWorkflowlWithId(activity.getWorkflowRevisionid());


    List<DAGTask> tasks = revision.getDag().getTasks();
    for (DAGTask task : tasks) {
      if (TaskType.eventwait.equals(task.getType())) {
        List<KeyValuePair> coreProperties = task.getProperties();
        if (coreProperties != null) {
          KeyValuePair coreProperty = coreProperties.stream()
              .filter(c -> "topic".contains(c.getKey())).findAny().orElse(null);

          if (coreProperty != null && topic.equals(coreProperty.getValue())) {

            String text = coreProperty.getValue();
            ControllerRequestProperties properties = propertyManager
                .buildRequestPropertyLayering(null, activityId, activity.getWorkflowId());
            text = propertyManager.replaceValueWithProperty(text, activityId, properties);

            String taskId = task.getTaskId();
            TaskExecutionEntity taskExecution =
                this.taskActivityService.findByTaskIdAndActivityId(taskId, activityId);
            if (taskExecution != null) {
              LOGGER.info("[{}] Found task id: {} ", activityId, taskExecution.getId());
              taskExecution.setPreApproved(true);
              this.taskActivityService.save(taskExecution);

              ids.add(taskExecution.getId());
            }
          }
        }
      }
    }
    LOGGER.info("[{}] No task activity ids found for topic: {}", activityId, topic);
    return ids;
  }

  @Override
  @Async("flowAsyncExecutor")
  public void submitActivity(String taskActivityId, String taskStatus,
      Map<String, String> outputProperties) {

    LOGGER.info("submitActivity: {}", taskStatus);

    TaskStatus status = TaskStatus.completed;
    if ("success".equals(taskStatus)) {
      status = TaskStatus.completed;
    } else if ("failure".equals(taskStatus)) {
      status = TaskStatus.failure;
    }

    LOGGER.info("Submit Activity (Task Status): {}", status.toString());


    TaskExecutionEntity taskExecution = this.taskActivityService.findById(taskActivityId);
    if (taskExecution != null && !taskExecution.getFlowTaskStatus().equals(TaskStatus.notstarted)) {
      InternalTaskResponse request = new InternalTaskResponse();
      request.setActivityId(taskActivityId);
      request.setStatus(status);

      if (outputProperties != null) {
        request.setOutputProperties(outputProperties);
      }

      endTask(request);
    }
  }
}
