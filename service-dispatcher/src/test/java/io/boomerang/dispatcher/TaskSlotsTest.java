package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.TaskRun;
import org.junit.jupiter.api.Test;

class TaskSlotsTest {

  private static TaskRun taskRun(RunPhase phase, RunStatus status) {
    TaskRun taskRun = new TaskRun();
    taskRun.setPhase(phase);
    taskRun.setStatus(status);
    return taskRun;
  }

  @Test
  void freeSlotsAreTheCapLessWhatIsInFlight() {
    TaskSlots slots = new TaskSlots(3);
    slots.take();
    slots.take();

    assertThat(slots.free()).isEqualTo(1);
  }

  @Test
  void aFullDispatcherHasNoFreeSlotsRatherThanANegativeCount() {
    TaskSlots slots = new TaskSlots(1);
    slots.take();
    slots.take();

    assertThat(slots.free()).isZero();
  }

  @Test
  void releasingMoreThanWasTakenNeverGoesBelowEmpty() {
    TaskSlots slots = new TaskSlots(2);
    slots.release();

    assertThat(slots.free()).isEqualTo(2);
  }

  @Test
  void noCapMeansNoLimitOnThePoll() {
    assertThat(new TaskSlots(0).free()).isNull();
  }

  @Test
  void onlyAnExecutionClaimOccupiesASlot() {
    assertThat(TaskSlots.isExecutionClaim(taskRun(RunPhase.queued, RunStatus.ready))).isTrue();
    assertThat(TaskSlots.isExecutionClaim(taskRun(RunPhase.completed, RunStatus.cancelled)))
        .isFalse();
    assertThat(TaskSlots.isExecutionClaim(taskRun(RunPhase.completed, RunStatus.timedout)))
        .isFalse();
  }
}
