package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.model.TaskResponse;
import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.error.TaskExecutionException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.boomerang.common.model.RunResult;
import java.util.List;

/**
 * Pins the dispatch half of the claim contract. The engine hands the agent a run whose phase is
 * already {@code queued} - the claim IS the pickup, and it advances the run from pending (the
 * engine side is verified by DispatcherQueueClaimTest). So the agent must dispatch on {@code queued};
 * guarding on {@code pending} - a phase a claimed run never carries here - silently drops the run.
 */
class QueueServiceDispatchTest {

  private final WorkflowService workflowService = mock(WorkflowService.class);
  private final WorkspaceService workspaceService = mock(WorkspaceService.class);
  private final TaskService taskService = mock(TaskService.class);
  private final EngineClient engineClient = mock(EngineClient.class);
  private final LeaseRegistry leaseRegistry = new LeaseRegistry();

  private final QueueService queueService =
      new QueueService(workflowService, workspaceService, taskService, engineClient, leaseRegistry);

  private static TaskRun taskRun(RunPhase phase) {
    TaskRun run = new TaskRun();
    run.setId("task-1");
    run.setType(TaskType.template);
    run.setPhase(phase);
    run.setStatus(RunStatus.ready);
    return run;
  }

  private static WorkflowRun workflowRun(RunPhase phase) {
    WorkflowRun run = new WorkflowRun();
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
  void failedTaskStillCarriesResultsWrittenBeforeFailure() {
    // A Task can write its Result Parameters and still exit non-zero (e.g. an HTTP Task hitting a
    // 404 that records the status code before failing) - the executor reports that as a
    // TaskExecutionException carrying the Results, not a plain failure with none.
    List<RunResult> results = List.of(new RunResult("statusCode", "404"));
    when(taskService.execute(any()))
        .thenThrow(new TaskExecutionException(results, "TaskExecutionError - exited with code 1"));

    queueService.processTaskRun(taskRun(RunPhase.queued));

    ArgumentCaptor<TaskRunEndRequest> endRequestCaptor =
        ArgumentCaptor.forClass(TaskRunEndRequest.class);
    verify(engineClient).endTask(eq("task-1"), endRequestCaptor.capture());
    assertEquals(RunStatus.failed, endRequestCaptor.getValue().getStatus());
    assertEquals(results, endRequestCaptor.getValue().getResults());
  }

  @Test
  void pendingTaskIsNotDispatched() {
    queueService.processTaskRun(taskRun(RunPhase.pending));

    verify(engineClient, never()).startTask(any());
  }

  @Test
  void queuedWorkflowIsDispatched() {
    queueService.processWorkflowRun(workflowRun(RunPhase.queued));

    verify(workflowService).execute(any(WorkflowRun.class));
    verify(engineClient).startWorkflow("wf-1");
  }

  @Test
  void genericFailureEndsTheTaskAsDispatchErrorAndClearsTheLease() {
    when(taskService.execute(any())).thenThrow(new RuntimeException("boom"));
    leaseRegistry.beat("task-1");

    queueService.processTaskRun(taskRun(RunPhase.queued));

    ArgumentCaptor<TaskRunEndRequest> captor = ArgumentCaptor.forClass(TaskRunEndRequest.class);
    verify(engineClient).endTask(eq("task-1"), captor.capture());
    assertEquals(RunStatus.failed, captor.getValue().getStatus());
    assertEquals("DispatchError", captor.getValue().getStatusReason());
    assertTrue(leaseRegistry.aliveWithin(Duration.ofMinutes(1)).isEmpty());
  }

  @Test
  void taskExecutionExceptionForwardsItsStatusReasonAndClearsTheLease() {
    when(taskService.execute(any())).thenThrow(new TaskExecutionException("OOMKilled", "boom"));
    leaseRegistry.beat("task-1");

    queueService.processTaskRun(taskRun(RunPhase.queued));

    ArgumentCaptor<TaskRunEndRequest> captor = ArgumentCaptor.forClass(TaskRunEndRequest.class);
    verify(engineClient).endTask(eq("task-1"), captor.capture());
    assertEquals(RunStatus.failed, captor.getValue().getStatus());
    assertEquals("OOMKilled", captor.getValue().getStatusReason());
    assertTrue(leaseRegistry.aliveWithin(Duration.ofMinutes(1)).isEmpty());
  }
}
