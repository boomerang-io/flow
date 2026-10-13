package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.WorkflowRun;

/**
 * Prepares what a workflow run needs before its tasks run - the storage for the workspaces it
 * declares. A dispatcher that runs nothing on shared storage supplies none, and the SDK then never
 * polls the workflow queue.
 */
@FunctionalInterface
public interface WorkflowHandler {

  /**
   * Provision a claimed workflow run. Returning starts the run; throwing reports nothing, so the
   * engine releases the claim after a grace and hands the run out again, failing it after three
   * attempts. Provisioning MUST be idempotent: a run may arrive more than once.
   */
  void provision(WorkflowRun run) throws Exception;
}
