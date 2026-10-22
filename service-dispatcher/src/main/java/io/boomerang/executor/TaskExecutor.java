package io.boomerang.executor;

import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-task execution runtime. TaskService dispatches every Task to exactly one
 * implementation, selected at startup via configuration.
 */
public interface TaskExecutor {

  /** Creates the runtime object for the Task; must be followed by {@link #watch}. */
  void create(TaskRun task, Long timeoutMinutes) throws InterruptedException, ParseException;

  /**
   * Blocks until the Task finishes. Returns its declared Results, or throws {@code
   * BoomerangException(TASK_EXECUTION_ERROR)} when the Task failed or timed out.
   */
  List<RunResult> watch(TaskRun task, Long timeoutMinutes) throws InterruptedException;

  /** Cancels the running Task; throws {@code BoomerangException(TASK_EXECUTION_ERROR)} when none is found. */
  void cancel(TaskRun task);

  /** Deletes the Task's runtime object. A no-op when none is found. */
  void delete(TaskRun task);

  /**
   * The labels that select the pod of the runtime object {@link #create} made or adopted for the
   * Task, known from then until {@link #forgetRuntimeObject}; empty when this executor holds none
   * for that Task. Every attempt of a Task carries the same task labels, so those alone would
   * also select an earlier attempt's pod.
   */
  Optional<Map<String, String>> runtimePodSelector(String taskRunId);

  /** Forgets the Task's runtime object; called once nothing more is read from it. */
  void forgetRuntimeObject(String taskRunId);
}
