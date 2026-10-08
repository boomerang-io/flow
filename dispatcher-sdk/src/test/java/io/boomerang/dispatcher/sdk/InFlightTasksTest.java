package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InFlightTasksTest {

  @Test
  void freeCapacityIsTheCapLessWhatIsInFlight() {
    InFlightTasks tasks = new InFlightTasks(() -> 3);
    tasks.add("task-1");
    tasks.add("task-2");

    assertThat(tasks.free()).isEqualTo(1);
  }

  @Test
  void aFullPoolHasNoFreeCapacityRatherThanANegativeCount() {
    AtomicInteger cap = new AtomicInteger(2);
    InFlightTasks tasks = new InFlightTasks(cap::get);
    tasks.add("task-1");
    tasks.add("task-2");
    cap.set(1);

    assertThat(tasks.free()).isZero();
  }

  @Test
  void theCapIsReadOnEveryPollSoItCanChangeAtRuntime() {
    AtomicInteger cap = new AtomicInteger(5);
    InFlightTasks tasks = new InFlightTasks(cap::get);
    tasks.add("task-1");

    cap.set(10);

    assertThat(tasks.free()).isEqualTo(9);
  }

  @Test
  void noCapMeansNoLimitOnThePoll() {
    assertThat(new InFlightTasks(() -> 0).free()).isNull();
  }

  @Test
  void removingWhatWasNeverAddedChangesNothing() {
    InFlightTasks tasks = new InFlightTasks(() -> 2);
    tasks.remove("task-1");

    assertThat(tasks.free()).isEqualTo(2);
  }

  @Test
  void theIdsAreWhatTheHeartbeatRenewsUntilEachIsRemoved() {
    InFlightTasks tasks = new InFlightTasks(() -> 0);
    tasks.add("task-1");
    tasks.add("task-2");
    tasks.remove("task-1");

    assertThat(tasks.ids()).containsExactly("task-2");
    assertThat(tasks.contains("task-1")).isFalse();
  }

  @Test
  void aTaskIsInFlightOnce() {
    InFlightTasks tasks = new InFlightTasks(() -> 0);

    assertThat(tasks.add("task-1")).isTrue();
    assertThat(tasks.add("task-1")).isFalse();
    assertThat(tasks.size()).isEqualTo(1);
  }
}
