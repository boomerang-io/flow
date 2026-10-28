package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A claimed workflow run arrives already queued - the claim is the pickup - so it is provisioned
 * on queued; its start follows the storage, never precedes it.
 */
class WorkflowRunnerTest {

  private final FakeEngine engine = new FakeEngine();
  private final List<String> provisioned = new CopyOnWriteArrayList<>();

  private static WorkflowRun workflowRun(RunPhase phase) {
    WorkflowRun run = new WorkflowRun();
    run.setId("wf-1");
    run.setPhase(phase);
    run.setStatus(RunStatus.ready);
    return run;
  }

  @Test
  void aQueuedRunIsProvisionedThenStarted() {
    new WorkflowRunner(
            engine,
            run -> {
              assertThat(engine.workflowStarts).isEmpty();
              provisioned.add(run.getId());
            },
            Runnable::run,
            () -> "d-1")
        .accept(workflowRun(RunPhase.queued));

    assertThat(provisioned).containsExactly("wf-1");
    assertThat(engine.workflowStarts).containsExactly("wf-1");
  }

  @Test
  void aCompletedRunIsIgnored() {
    // Storage is released by asking the engine which owners are finished, not by the queue.
    new WorkflowRunner(engine, run -> provisioned.add(run.getId()), Runnable::run, () -> "d-1")
        .accept(workflowRun(RunPhase.completed));

    assertThat(provisioned).isEmpty();
    assertThat(engine.workflowStarts).isEmpty();
  }

  @Test
  void aRunThatCannotBeProvisionedIsNotStartedAndItsCauseIsReported() {
    // The engine shows the innermost cause on the run and decides whether to retry.
    new WorkflowRunner(
            engine,
            run -> {
              throw new IllegalStateException(
                  "An error has occurred.", new java.net.ConnectException("Connection refused"));
            },
            Runnable::run,
            () -> "d-1")
        .accept(workflowRun(RunPhase.queued));

    assertThat(engine.workflowStarts).isEmpty();
    assertThat(engine.provisionFailures).containsExactly("d-1 wf-1: Connection refused");
  }
}
