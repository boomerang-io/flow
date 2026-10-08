package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import java.util.concurrent.Executor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * One claimed workflow run: provision it on a virtual thread, then tell the engine it may start.
 * A run arrives already {@code queued} - the claim is the pickup - so only a queued, ready run is
 * provisioned; a failure reports nothing and the engine hands the run out again after a grace.
 */
final class WorkflowRunner {

  private static final Log LOGGER = LogFactory.getLog(WorkflowRunner.class);

  private final DispatcherClient client;
  private final WorkflowHandler handler;
  private final Executor executor;

  WorkflowRunner(DispatcherClient client, WorkflowHandler handler, Executor executor) {
    this.client = client;
    this.handler = handler;
    this.executor = executor;
  }

  /** Take one claim off a poll. Returns at once; the work runs on a virtual thread. */
  void accept(WorkflowRun run) {
    if (RunPhase.queued.equals(run.getPhase()) && RunStatus.ready.equals(run.getStatus())) {
      executor.execute(() -> provision(run));
    } else {
      LOGGER.debug("Skipping " + run + ": not a queued, ready run.");
    }
  }

  void provision(WorkflowRun run) {
    try {
      LOGGER.info("WorkflowRun (" + run.getId() + ") provisioning.");
      // Provision first: starting the run admits its first tasks, which need the storage there.
      handler.provision(run);
      client.startWorkflowRun(run.getId());
    } catch (Exception e) {
      // The run stays claimed; the engine releases a stale provisioning claim for another attempt
      // and fails the run once its attempts are spent.
      LOGGER.error("WorkflowRun (" + run.getId() + ") could not be provisioned.", e);
    }
  }
}
