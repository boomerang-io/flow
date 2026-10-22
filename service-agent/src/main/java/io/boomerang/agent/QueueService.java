package io.boomerang.agent;

import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.error.BoomerangException;
import io.boomerang.agent.model.TaskResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class QueueService {
  private static final Logger LOGGER = LogManager.getLogger(QueueService.class);

  private final WorkflowService workflowService;

  private final WorkspaceService workspaceService;

  private final TaskService taskService;

  private final EngineClient engineClient;

  public QueueService(
      WorkflowService workflowService,
      WorkspaceService workspaceService,
      TaskService taskService,
      @Lazy EngineClient engineClient) {
    this.workflowService = workflowService;
    this.workspaceService = workspaceService;
    this.taskService = taskService;
    this.engineClient = engineClient;
  }

  @Async
  public void processWorkflowRun(WorkflowRun request) {
    try {
      LOGGER.debug(request.toString());
      if (RunPhase.pending.equals(request.getPhase())
          && RunStatus.ready.equals(request.getStatus())) {
        LOGGER.info("Executing WorkflowRun...");
        // The execute is before communicating with the Engine
        // as starting the workflow will kick off the first task(s) and
        // dependencies at the workflow level (Workspaces) need to be there prior
        workflowService.execute(request);
        engineClient.startWorkflow(request.getId());
      } else if (RunPhase.completed.equals(request.getPhase())) {
        LOGGER.info("Finalizing WorkflowRun...");
        workflowService.terminate(request);
        engineClient.finalizeWorkflow(request.getId());
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
      if ((TaskType.template.equals(request.getType())
              || TaskType.custom.equals(request.getType())
              || TaskType.script.equals(request.getType()))
          && RunPhase.pending.equals(request.getPhase())
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
      } else if ((TaskType.template.equals(request.getType())
              || TaskType.custom.equals(request.getType())
              || TaskType.script.equals(request.getType()))
          && RunPhase.completed.equals(request.getPhase())
          && (RunStatus.cancelled.equals(request.getStatus())
              || RunStatus.timedout.equals(request.getStatus()))) {
        LOGGER.info("Cancelling TaskRun...");
        taskService.terminate(request);
      } else {
        // TODO turn this into the types of tasks that this Agent supports
        LOGGER.info(
            "Skipping TaskRun as criteria not met; (Type: template, custom, or script), (Status: ready, cancelled, timedout), and (Phase: pending, completed).");
      }
    } catch (BoomerangException e) {
      LOGGER.fatal("Failed to execute TaskRun.", e);
      TaskRunEndRequest endRequest = new TaskRunEndRequest();
      endRequest.setStatus(RunStatus.failed);
      endRequest.setStatusMessage(e.getMessage());
      engineClient.endTask(request.getId(), endRequest);
    } catch (Exception e) {
      LOGGER.fatal("A fatal error has occurred while processing the message!", e);
    }
  }
}
