package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.TaskRun;

/**
 * The work a dispatcher does for a task run. The SDK has already told the engine the task is
 * starting before {@link #run} is called, and reports the end from what it returns or throws; a
 * handler never calls the engine itself.
 */
public interface TaskHandler {

  /**
   * Run the task on the calling thread, a virtual thread of its own, and return its results. The
   * task's lease is renewed for as long as this runs. Throw {@link TaskFailure} for a typed
   * failure; any other exception fails the task as {@value TaskFailure#DISPATCH_ERROR}.
   */
  TaskResult run(TaskRun task, TaskContext context) throws Exception;

  /**
   * Stop any work held for a task the engine has cancelled, timed out or requeued. Called for
   * every terminate order, including one for a task this process is not running - work may have
   * outlived an earlier process - so nothing left to stop is the ordinary case. The SDK reports no
   * end for a terminated task.
   */
  default void cancel(TaskRun task) throws Exception {}
}
