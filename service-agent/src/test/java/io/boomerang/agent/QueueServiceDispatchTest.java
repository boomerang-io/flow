package io.boomerang.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.agent.model.TaskResponse;
import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowRunClaim;
import org.junit.jupiter.api.Test;

/**
 * Pins the dispatch half of the claim contract. The engine hands the agent a run whose phase is
 * already {@code queued} - the claim IS the pickup, and it advances the run from pending (the
 * engine side is verified by AgentQueueClaimTest). So the agent must dispatch on {@code queued};
 * guarding on {@code pending} - a phase a claimed run never carries here - silently drops the run.
 */
class QueueServiceDispatchTest {

  private final WorkflowService workflowService = mock(WorkflowService.class);
  private final WorkspaceService workspaceService = mock(WorkspaceService.class);
  private final TaskService taskService = mock(TaskService.class);
  private final EngineClient engineClient = mock(EngineClient.class);

  private final QueueService queueService =
      new QueueService(workflowService, workspaceService, taskService, engineClient);

  private static TaskRun taskRun(RunPhase phase) {
    TaskRun run = new TaskRun();
    run.setId("task-1");
    run.setType(TaskType.template);
    run.setPhase(phase);
    run.setStatus(RunStatus.ready);
    return run;
  }

  private static WorkflowRunClaim workflowRun(RunPhase phase) {
    WorkflowRunClaim run = new WorkflowRunClaim();
    run.setId("wf-1");
    run.setPhase(phase);
    run.setStatus(RunStatus.ready);
    return run;
  }

  @Test
  void queuedTaskIsDispatched() {
    when(taskService.execute(any())).thenReturn(new TaskResponse());

    queueService.processTaskRun(taskRun(RunPhase.queued));

    verify(engineClient).startTask("task-1");
    verify(engineClient).endTask(eq("task-1"), any(TaskRunEndRequest.class));
  }

  @Test
  void pendingTaskIsNotDispatched() {
    queueService.processTaskRun(taskRun(RunPhase.pending));

    verify(engineClient, never()).startTask(any());
  }

  @Test
  void queuedWorkflowIsDispatched() {
    queueService.processWorkflowRun(workflowRun(RunPhase.queued));

    verify(workflowService).execute(any(WorkflowRunClaim.class));
    verify(engineClient).startWorkflow("wf-1");
  }
}
