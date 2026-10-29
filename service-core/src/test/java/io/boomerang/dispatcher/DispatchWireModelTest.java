package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.dispatcher.model.DispatchTaskRun;
import io.boomerang.dispatcher.model.DispatchWorkflowRun;
import io.boomerang.dispatcher.model.HandOutTaskRun;
import io.boomerang.dispatcher.model.TaskRunRetry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire hands a dispatcher the attempt an order is for, the attempts a requeue superseded and
 * the typed cause a task or run ended with, so a dispatcher that applies its own retry budget can
 * count the engine's attempts; the retry state itself stays on the entity.
 */
class DispatchWireModelTest {

  @Test
  void aTaskOrderCarriesItsAttemptTheSupersededOnesAndTheReason() {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId("t1");
    RunClaim claim = new RunClaim();
    claim.setBy("disp-a");
    claim.setSeq(3L);
    entity.setClaim(claim);
    RunRetry retry = new RunRetry();
    retry.setMax(2);
    retry.setFailures(2);
    entity.setRetry(retry);
    RunAttempt first = new RunAttempt();
    first.setNumber(1);
    first.setStatus(RunStatus.failed);
    first.setStatusReason("JobFailed");
    RunAttempt second = new RunAttempt();
    second.setNumber(2);
    second.setStatus(RunStatus.timedout);
    second.setStatusReason("LeaseExpired");
    entity.setAttempts(List.of(first, second));
    entity.setStatus(RunStatus.cancelled);
    entity.setPhase(RunPhase.completed);
    entity.setStatusReason("ConcurrencyLimit");

    DispatchTaskRun wire = DispatchTaskRun.of(new TaskRun(entity));

    assertThat(wire.attempt()).isEqualTo(3);
    assertThat(wire.attempts()).extracting(RunAttempt::getStatusReason)
        .containsExactly("JobFailed", "LeaseExpired");
    assertThat(wire.statusReason()).isEqualTo("ConcurrencyLimit");
    assertThat(wire.retry()).as("a plain model carries no retry state").isNull();
  }

  @Test
  void aHandOutCarriesTheNodesBudgetAndWhatIsSpentButNotTheRest() {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId("t2");
    RunRetry retry = new RunRetry();
    retry.setMax(3);
    retry.setFailures(1);
    retry.setCount(2);
    retry.setAfter(new java.util.Date());
    entity.setRetry(retry);

    DispatchTaskRun wire = DispatchTaskRun.of(new HandOutTaskRun(entity));

    assertThat(wire.retry()).isEqualTo(new TaskRunRetry(3, 1));
    TaskRun answered = new TaskRun(entity);
    answered.setTaskRef("echo");
    assertThat(DispatchTaskRun.of(new HandOutTaskRun(answered, entity.getRetry())).retry())
        .as("an engine answer wrapped with the entity's retry state carries it too")
        .isEqualTo(new TaskRunRetry(3, 1));
    assertThat(DispatchTaskRun.of(new HandOutTaskRun(new TaskRunEntity())).retry())
        .as("a node that asked for no retry and was never requeued holds none")
        .isNull();
  }

  @Test
  void aRunOrderCarriesTheRunsReason() {
    WorkflowRun run = new WorkflowRun();
    run.setId("r1");
    run.setStatusReason("DeadlineExceeded");

    assertThat(DispatchWorkflowRun.of(run).statusReason()).isEqualTo("DeadlineExceeded");
    assertThat(DispatchWorkflowRun.of(new WorkflowRun()).statusReason()).isNull();
  }

  @Test
  void aFirstAttemptCarriesNoAttemptsAndNoReason() {
    TaskRunEntity entity = new TaskRunEntity();
    RunClaim claim = new RunClaim();
    claim.setSeq(1L);
    entity.setClaim(claim);

    DispatchTaskRun wire = DispatchTaskRun.of(new TaskRun(entity));

    assertThat(wire.attempt()).isEqualTo(1);
    assertThat(wire.attempts()).isNull();
    assertThat(wire.statusReason()).isNull();
  }
}
