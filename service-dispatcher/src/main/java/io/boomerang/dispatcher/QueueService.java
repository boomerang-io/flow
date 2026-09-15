package io.boomerang.dispatcher;

import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import java.util.List;
import java.util.stream.Collectors;
import io.boomerang.dispatcher.model.TaskResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class QueueService {
  private static final Logger LOGGER = LogManager.getLogger(QueueService.class);

  /*
   * The types this dispatcher can actually run in a container. Which of them it is handed is the
   * engine's decision, made from the types registered at startup (flow.dispatcher.task-types); this
   * is the second gate, so a type the dispatcher has no runtime for is skipped rather than failed.
   * `ai` runs the Flow-shipped worker image the dispatcher resolves for it (TaskImageResolver).
   */
  private static final List<TaskType> EXECUTABLE_TYPES =
      List.of(TaskType.template, TaskType.custom, TaskType.script, TaskType.ai);

  private static boolean isExecutable(TaskType type) {
    return EXECUTABLE_TYPES.contains(type);
  }

  private final WorkflowService workflowService;

  private final WorkspaceService workspaceService;

  private final TaskService taskService;

  private final EngineClient engineClient;

  private final LeaseRegistry leaseRegistry;

  public QueueService(
      WorkflowService workflowService,
      WorkspaceService workspaceService,
      TaskService taskService,
      @Lazy EngineClient engineClient,
      LeaseRegistry leaseRegistry) {
    this.workflowService = workflowService;
    this.workspaceService = workspaceService;
    this.taskService = taskService;
    this.engineClient = engineClient;
    this.leaseRegistry = leaseRegistry;
  }

  @Async
  public void processWorkflowRun(WorkflowRun request) {
    try {
      LOGGER.debug(request.toString());
      // A claimed run arrives already in phase queued - the claim (which is the pickup) advances
      // it from pending. Dispatch on queued; pending is a phase a claimed run never carries here.
      if (RunPhase.queued.equals(request.getPhase())
          && RunStatus.ready.equals(request.getStatus())) {
        LOGGER.info("Executing WorkflowRun...");
        // The execute is before communicating with the Engine
        // as starting the workflow will kick off the first task(s) and
        // dependencies at the workflow level (Workspaces) need to be there prior
        workflowService.execute(request);
        engineClient.startWorkflow(request.getId());
      }
    } catch (BoomerangException e) {
      LOGGER.fatal("A fatal error has occurred while processing the message!", e);
      // TODO catch failure and end workflow with error status
    } catch (Exception e) {
      LOGGER.fatal("A fatal error has occurred while processing the message!", e);
    }
  }

  @Async
  public void processTaskRun(TaskRun request) {
    try {
      LOGGER.debug(request.toString());
      if (isExecutable(request.getType())
          && RunPhase.queued.equals(request.getPhase())
          && RunStatus.ready.equals(request.getStatus())) {
        LOGGER.info("Executing TaskRun...");
        // Communicate the start with the Engine
        // prior to Tekton starting as it is a blocking Watch call.
        engineClient.startTask(request.getId());
        TaskResponse response = new TaskResponse();
        response = taskService.execute(request);
        TaskRunEndRequest endRequest = new TaskRunEndRequest();
        endRequest.setStatus(RunStatus.succeeded);
        endRequest.setStatusMessage(response.getMessage());
        endRequest.setResults(response.getResults());
        engineClient.endTask(request.getId(), endRequest);
      } else if (isExecutable(request.getType())
          && RunPhase.completed.equals(request.getPhase())
          && (RunStatus.cancelled.equals(request.getStatus())
              || RunStatus.timedout.equals(request.getStatus()))) {
        LOGGER.info("Cancelling TaskRun...");
        taskService.terminate(request);
      } else {
        // TODO turn this into the types of tasks that this Agent supports
        LOGGER.info(
            "Skipping TaskRun as criteria not met; (Type: "
                + EXECUTABLE_TYPES.stream().map(TaskType::getLabel).collect(Collectors.joining(", "))
                + "), (Status: ready, cancelled, timedout), and (Phase: queued, completed).");
      }
    } catch (BoomerangException e) {
      LOGGER.fatal("Failed to execute TaskRun.", e);
      if (e instanceof TaskExecutionException t) {
        endFailed(request.getId(), t.getStatusReason(), e.getMessage(), t.getResults());
      } else {
        endFailed(request.getId(), "DispatchError", e.getMessage(), List.of());
      }
    } catch (Exception e) {
      LOGGER.error("A fatal error has occurred while processing the message!", e);
      endFailed(request.getId(), "DispatchError", e.getMessage(), List.of());
    } finally {
      leaseRegistry.remove(request.getId());
    }
  }

  private void endFailed(
      String taskRunId, String statusReason, String message, List<RunResult> results) {
    try {
      TaskRunEndRequest endRequest = new TaskRunEndRequest();
      endRequest.setStatus(RunStatus.failed);
      endRequest.setStatusReason(statusReason);
      endRequest.setStatusMessage(message);
      // Carries any Result Parameters the Task wrote before it failed.
      endRequest.setResults(results);
      engineClient.endTask(taskRunId, endRequest);
    } catch (Exception e) {
      LOGGER.error("Failed to report TaskRun ({}) failure to the engine.", taskRunId, e);
    }
  }
}
