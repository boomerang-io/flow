package io.boomerang.aspect;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.engine.EventSinkService;
import io.boomerang.engine.repository.TaskRunRepository;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
@ConditionalOnProperty(
    name = "flow.events.sink.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class TaskRunEntityUpdateInterceptor {
  private static final Logger LOGGER = LogManager.getLogger();

  private final TaskRunRepository taskRunRepository;
  private final EventSinkService eventSinkService;

  public TaskRunEntityUpdateInterceptor(
      TaskRunRepository taskRunRepository, EventSinkService eventSinkService) {
    this.taskRunRepository = taskRunRepository;
    this.eventSinkService = eventSinkService;
  }

  @Before(
      "execution(* io.boomerang.engine.repository.TaskRunRepository.save(..))"
          + " && args(entityToBeSaved)")
  public void saveInvoked(JoinPoint thisJoinPoint, Object entityToBeSaved) {
    LOGGER.info(
        "Intercepted save action on entity {} from {}",
        entityToBeSaved,
        thisJoinPoint.getSignature().getDeclaringTypeName());
    if (entityToBeSaved instanceof TaskRunEntity) {
      taskRunEntityToBeUpdated((TaskRunEntity) entityToBeSaved);
    }
  }

  private void taskRunEntityToBeUpdated(TaskRunEntity newEntity) {
    // Check if WorkflowRun and Workflow IDs are not empty
    if (StringUtils.isNotBlank(newEntity.getWorkflowRunRef())
        && StringUtils.isNotBlank(newEntity.getId())) {

      taskRunRepository
          .findById(newEntity.getId())
          .ifPresent(
              oldEntity -> {
                // Retrieve old entity and compare the statuses
                if (oldEntity.getStatus() != newEntity.getStatus()) {

                  // Status has changed, publish status update CloudEvent
                  // TODO: separate out phase and status events
                  eventSinkService.publishStatusCloudEvent(newEntity);
                  LOGGER.info(
                      "TaskRun Status / Phase has changed [Status: "
                          + oldEntity.getStatus()
                          + ", Phase: "
                          + oldEntity.getPhase()
                          + "] -> [Status: "
                          + newEntity.getStatus()
                          + ", Phase: "
                          + newEntity.getPhase()
                          + "].");
                }
              });
    }
  }
}
