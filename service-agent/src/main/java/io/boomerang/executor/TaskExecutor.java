package io.boomerang.executor;

import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import java.text.ParseException;
import java.util.List;

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
}
