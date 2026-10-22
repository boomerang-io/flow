package io.boomerang.aspect;

import io.boomerang.audit.AuditInterceptor;
import io.boomerang.audit.AuditType;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.engine.EventSinkService;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class WorkflowRunEntityUpdateInterceptor {
  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowRunRepository workflowRunRepository;
  private final EventSinkService eventSinkService;
  private final AuditInterceptor auditInterceptor;

  public WorkflowRunEntityUpdateInterceptor(
      WorkflowRunRepository workflowRunRepository,
      EventSinkService eventSinkService,
      AuditInterceptor auditInterceptor) {
    this.workflowRunRepository = workflowRunRepository;
    this.eventSinkService = eventSinkService;
    this.auditInterceptor = auditInterceptor;
  }

  @Before(
      "execution(* io.boomerang.engine.repository.WorkflowRunRepository.save(..))"
          + " && args(entityToBeSaved)")
  @ConditionalOnProperty(
      name = "flow.events.sink.enabled",
      havingValue = "true",
      matchIfMissing = false)
  public void saveInvoked(JoinPoint thisJoinPoint, Object entityToBeSaved) {
    LOGGER.info(
        "Intercepted save action on entity {} from {}",
        entityToBeSaved,
        thisJoinPoint.getSignature().getDeclaringTypeName());

    if (entityToBeSaved instanceof WorkflowRunEntity) {
      workflowRunEntityToBeUpdated((WorkflowRunEntity) entityToBeSaved);
    }
  }

  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.engine.repository.WorkflowRunRepository.save(..)) && args(request)",
      returning = "entity")
  @ConditionalOnProperty(name = "flow.audit.enabled", havingValue = "true", matchIfMissing = false)
  public void saveInvoked(
      JoinPoint thisJoinPoint, WorkflowRunEntity request, WorkflowRunEntity entity) {

    LOGGER.info(
        "Intercepted save action on entity {} from {}",
        request,
        thisJoinPoint.getSignature().getDeclaringTypeName());
    if (request.getStatus().equals(RunStatus.notstarted)) {
      Map<String, String> data = new HashMap<>();
      data.put("duration", String.valueOf(entity.getDuration()));
      data.put("workflowRef", request.getWorkflowRef());
      data.put("phase", request.getPhase().toString());
      data.put("status", request.getStatus().toString());
      auditInterceptor.createWfRunLog(entity.getId(), entity.getWorkflowRef(), Optional.of(data));
    } else {
      LOGGER.info(
          "Status Label: {}, Audit Type: {}",
          entity.getStatus().getStatus(),
          AuditType.valueOfLabel(entity.getStatus().getStatus()));
      Map<String, String> data = new HashMap<>();
      data.put("duration", String.valueOf(entity.getDuration()));
      data.put("phase", request.getPhase().toString());
      data.put("status", request.getStatus().toString());
      if (request.getStartTime() != null) {
        data.put("startTime", request.getStartTime().toString());
      }
      auditInterceptor.updateWfRunLog(
          AuditType.valueOfLabel(entity.getStatus().getStatus()),
          entity.getId(),
          Optional.of(data));
    }
  }

  private void workflowRunEntityToBeUpdated(WorkflowRunEntity newEntity) {
    // Check if activity and workflow IDs are not empty
    if (StringUtils.isNotBlank(newEntity.getWorkflowRef())
        && StringUtils.isNotBlank(newEntity.getId())) {

      // Retrieve old entity and compare the statuses
      workflowRunRepository
          .findById(newEntity.getId())
          .ifPresent(
              oldEntity -> {
                if (oldEntity.getStatus() != newEntity.getStatus()
                    || oldEntity.getPhase() != newEntity.getPhase()) {

                  // Status has changed, publish status update CloudEvent
                  //          eventingService.publishStatusCloudEvent(newActivityEntity);
                  // TODO: separate out phase and status events
                  eventSinkService.publishStatusCloudEvent(newEntity);

                  LOGGER.info(
                      "WorkflowRun Status / Phase has changed [Status: "
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
