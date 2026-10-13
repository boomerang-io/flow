package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.dispatcher.sdk.TaskContext;
import io.boomerang.dispatcher.sdk.TaskFailure;
import io.boomerang.dispatcher.sdk.TaskResult;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskDeletion;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The container task handler's outcome: the executor's results on success, its typed reason and
 * the results written before a failure, and a terminate order handed to the executor's cancel.
 */
class TaskServiceHandlerTest {

  private final TaskExecutor executor = mock(TaskExecutor.class);
  private final TaskImageResolver imageResolver = mock(TaskImageResolver.class);
  private final TaskService taskService = new TaskService(executor, imageResolver);

  @BeforeEach
  void setUp() {
    when(imageResolver.image(any())).thenReturn("alpine:3.19");
    ReflectionTestUtils.setField(taskService, "taskDeletion", TaskDeletion.Never);
    ReflectionTestUtils.setField(taskService, "taskTimeout", 60L);
  }

  private static TaskRun task(TaskType type) {
    TaskRun task = new TaskRun();
    task.setId("task-1");
    task.setType(type);
    return task;
  }

  private TaskFailure failureOf(TaskRun task) {
    return catchThrowableOfType(
        TaskFailure.class, () -> taskService.run(task, new TaskContext(task, "d-1", null)));
  }

  @Test
  void aTaskThatSucceedsReturnsTheExecutorsResults() throws Exception {
    List<RunResult> results = List.of(new RunResult("greeting", "hello"));
    when(executor.watch(any(), any())).thenReturn(results);
    TaskRun task = task(TaskType.template);

    TaskResult result = taskService.run(task, new TaskContext(task, "d-1", null));

    assertThat(result.getResults()).isEqualTo(results);
    assertThat(result.getMessage()).contains("task-1");
  }

  @Test
  void anAiTaskRunsLikeAnyOtherContainerType() throws Exception {
    // `ai` runs as a pod from the resolved worker image; only the image and command are resolved.
    when(executor.watch(any(), any())).thenReturn(List.of());
    TaskRun task = task(TaskType.ai);

    assertThat(taskService.run(task, new TaskContext(task, "d-1", null)).getResults()).isEmpty();
  }

  @Test
  void aFailedTaskCarriesItsReasonAndTheResultsWrittenBeforeIt() throws Exception {
    // A task can write its results and still exit non-zero (an HTTP task recording a 404).
    List<RunResult> results = List.of(new RunResult("statusCode", "404"));
    when(executor.watch(any(), any()))
        .thenThrow(new TaskExecutionException(results, "TaskExecutionError - exited with code 1"));

    TaskFailure failure = failureOf(task(TaskType.template));

    assertThat(failure.getStatusReason()).isEqualTo("JobFailed");
    assertThat(failure.getResults()).isEqualTo(results);
    assertThat(failure.getMessage()).isEqualTo("TaskExecutionError - exited with code 1");
  }

  @Test
  void aTypedFailureKeepsItsReason() throws Exception {
    when(executor.watch(any(), any())).thenThrow(new TaskExecutionException("OOMKilled", "boom"));

    assertThat(failureOf(task(TaskType.template)).getStatusReason()).isEqualTo("OOMKilled");
  }

  @Test
  void anyOtherExecutorErrorIsADispatchError() throws Exception {
    when(executor.watch(any(), any()))
        .thenThrow(new BoomerangException(BoomerangError.TASK_EXECUTION_ERROR, "PARAM_NAME_COLLISION"));

    assertThat(failureOf(task(TaskType.template)).getStatusReason()).isEqualTo("DispatchError");
  }

  @Test
  void aTaskWithNoImageIsADispatchError() {
    when(imageResolver.image(any())).thenReturn(null);

    assertThat(failureOf(task(TaskType.template)).getStatusReason()).isEqualTo("DispatchError");
  }

  @Test
  void aTerminateOrderCancelsTheRuntimeObject() {
    TaskRun task = task(TaskType.template);

    taskService.cancel(task);

    verify(executor).cancel(task);
  }
}
