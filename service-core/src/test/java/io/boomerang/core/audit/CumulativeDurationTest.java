package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.StatusReason;
import io.boomerang.core.audit.CumulativeDuration.TaskRunTime;
import io.boomerang.engine.EngineConstants;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A run's cumulative duration counts each dispatched task's time from its start call to its end:
 * every rule, every task type, the reasons that mean a task never ran on the current attempt and on
 * an earlier one, a task still running against one waiting for a dispatcher, an attempt that kept
 * an earlier attempt's start, and a negative figure.
 */
class CumulativeDurationTest {

  private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
  private static final List<String> REQUEUEABLE_REASONS =
      List.of(
          StatusReason.EXCEEDED_QUOTA,
          StatusReason.START_TIMEOUT,
          StatusReason.RATE_LIMITED,
          StatusReason.CLUSTER_UNAVAILABLE,
          StatusReason.DISPATCHER_STOPPING);

  @Test
  void theFiveReasonsAreTheEngineRequeueableSet() {
    assertThat(REQUEUEABLE_REASONS).containsExactlyInAnyOrderElementsOf(StatusReason.REQUEUEABLE);
  }

  // Rule 1: only the dispatched types count.
  @ParameterizedTest
  @EnumSource(TaskType.class)
  void onlyADispatchedTypeCounts(TaskType type) {
    long counted = CumulativeDuration.millis(completed(type, 60_000L), NOW);

    assertThat(counted)
        .isEqualTo(EngineConstants.REQUEUEABLE_TYPES.contains(type) ? 60_000L : 0L);
  }

  @Test
  void aTaskRunWithNoTypeGivesNothing() {
    assertThat(CumulativeDuration.millis(completed(null, 60_000L), NOW)).isZero();
  }

  // Rule 2: a for-each parent gives nothing; its items count.
  @Test
  void aForEachParentGivesNothingAndItsItemsCount() {
    TaskRunTime parent = task(TaskType.template, true, null, null, RunPhase.completed, at(-600), 600_000L, null, null);
    TaskRunTime item = task(TaskType.template, false, "parent", null, RunPhase.completed, at(-300), 30_000L, null, null);
    TaskRunTime itemWithForeach = task(TaskType.template, true, "parent", null, RunPhase.completed, at(-300), 20_000L, null, null);

    assertThat(CumulativeDuration.millis(parent, NOW)).isZero();
    assertThat(CumulativeDuration.millis(List.of(parent, item, itemWithForeach), NOW)).isEqualTo(50_000L);
  }

  // Rule 3: a task run a scoped retry carried over gives nothing.
  @Test
  void aCarriedOverTaskRunGivesNothing() {
    TaskRunTime reused =
        task(TaskType.template, false, null, "origin", RunPhase.completed, at(-60), 60_000L, null, null);

    assertThat(CumulativeDuration.millis(reused, NOW)).isZero();
  }

  // Rule 4: the current attempt.
  @ParameterizedTest
  @ValueSource(strings = {"ExceededQuota", "StartTimeout", "RateLimited", "ClusterUnavailable", "DispatcherStopping"})
  void aCurrentAttemptThatNeverRanGivesNothing(String reason) {
    TaskRunTime ended =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-60), 60_000L, reason, null);
    TaskRunTime running =
        task(TaskType.template, false, null, null, RunPhase.running, at(-60), 0L, reason, null);

    assertThat(CumulativeDuration.millis(ended, NOW)).isZero();
    assertThat(CumulativeDuration.millis(running, NOW)).isZero();
  }

  @Test
  void aCompletedAttemptCountsItsDuration() {
    TaskRunTime failed =
        task(TaskType.script, false, null, null, RunPhase.completed, at(-90), 90_000L, StatusReason.OOM_KILLED, null);

    assertThat(CumulativeDuration.millis(failed, NOW)).isEqualTo(90_000L);
  }

  @Test
  void aRunningAttemptCountsUpToNow() {
    TaskRunTime running =
        task(TaskType.custom, false, null, null, RunPhase.running, at(-45), 0L, null, null);

    assertThat(CumulativeDuration.millis(running, NOW)).isEqualTo(45_000L);
  }

  @ParameterizedTest
  @EnumSource(value = RunPhase.class, names = {"pending", "queued"})
  void anAttemptWaitingForADispatcherGivesNothing(RunPhase phase) {
    TaskRunTime waiting = task(TaskType.custom, false, null, null, phase, null, 0L, null, null);
    TaskRunTime claimed = task(TaskType.custom, false, null, null, phase, at(-45), 0L, null, null);

    assertThat(CumulativeDuration.millis(waiting, NOW)).isZero();
    assertThat(CumulativeDuration.millis(claimed, NOW)).isZero();
  }

  @Test
  void aRunningAttemptWithNoStartGivesNothing() {
    TaskRunTime running = task(TaskType.custom, false, null, null, RunPhase.running, null, 0L, null, null);

    assertThat(CumulativeDuration.millis(running, NOW)).isZero();
  }

  // Rule 5: earlier attempts add their duration unless they never ran.
  static Stream<Arguments> earlierAttemptReasons() {
    return Stream.concat(
        REQUEUEABLE_REASONS.stream().map(reason -> Arguments.of(reason, 0L)),
        Stream.of(
            Arguments.of(StatusReason.LEASE_EXPIRED, 20_000L),
            Arguments.of(StatusReason.DEADLINE_EXCEEDED, 20_000L),
            Arguments.of(StatusReason.JOB_FAILED, 20_000L),
            Arguments.of(null, 20_000L)));
  }

  @ParameterizedTest
  @MethodSource("earlierAttemptReasons")
  void anEarlierAttemptCountsUnlessItNeverRan(String reason, long expected) {
    // Claimed at -100 s, started at -80 s, ended at -60 s; the current attempt started at -30 s.
    RunAttempt first = attempt(at(-100), at(-60), 20_000L, reason);
    TaskRunTime retried =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-30), 30_000L, null, List.of(first));

    assertThat(CumulativeDuration.millis(retried, NOW)).isEqualTo(expected + 30_000L);
  }

  // Rule 7: a requeue keeps startTime, so an attempt that never started carries the earlier one's.
  @Test
  void anAttemptThatKeptAnEarlierStartGivesNothing() {
    // The first attempt started at -200 s and its dispatcher went quiet at -150 s. The second was
    // claimed at -140 s, never started, and was reaped at -100 s with the first's start: 100 s.
    RunAttempt first = attempt(at(-210), at(-150), 50_000L, StatusReason.LEASE_EXPIRED);
    RunAttempt second = attempt(at(-140), at(-100), 100_000L, StatusReason.LEASE_EXPIRED);
    TaskRunTime third =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-40), 40_000L, null, List.of(first, second));

    assertThat(CumulativeDuration.millis(third, NOW)).isEqualTo(50_000L + 40_000L);
  }

  @Test
  void anUnclaimedAttemptThatKeptAnEarlierStartGivesNothing() {
    RunAttempt first = attempt(at(-210), at(-150), 50_000L, StatusReason.JOB_FAILED);
    // Requeued unclaimed: no claim time, the stale start before the first attempt's end.
    RunAttempt second = attempt(null, at(-100), 100_000L, StatusReason.DEADLINE_EXCEEDED);
    TaskRunTime third =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-40), 40_000L, null, List.of(first, second));

    assertThat(CumulativeDuration.millis(third, NOW)).isEqualTo(90_000L);
  }

  @Test
  void aClaimedAttemptThatNeverStartedGivesNothing() {
    // No start call: the attempt's duration runs from its claim.
    RunAttempt claimedOnly = attempt(at(-200), at(-100), 100_000L, StatusReason.LEASE_EXPIRED);
    TaskRunTime second =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-40), 40_000L, null, List.of(claimedOnly));

    assertThat(CumulativeDuration.millis(second, NOW)).isEqualTo(40_000L);
  }

  @Test
  void aCurrentAttemptStillOnAnEarlierStartGivesNothing() {
    RunAttempt first = attempt(at(-210), at(-150), 50_000L, StatusReason.LEASE_EXPIRED);
    // Requeued and then cancelled before a start call: its duration runs from the first's start.
    TaskRunTime cancelled =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-200), 200_000L, null, List.of(first));
    TaskRunTime claimedAgain =
        task(TaskType.template, false, null, null, RunPhase.running, at(-150), 0L, null, List.of(first));

    assertThat(CumulativeDuration.millis(cancelled, NOW)).isEqualTo(50_000L);
    assertThat(CumulativeDuration.millis(claimedAgain, NOW)).isEqualTo(50_000L);
  }

  // Rule 6: a negative figure counts as nothing.
  @Test
  void aNegativeFigureCountsAsNothing() {
    RunAttempt negative = attempt(null, at(-100), -5_000L, StatusReason.JOB_FAILED);
    TaskRunTime clockSkew =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-40), -40_000L, null, null);
    TaskRunTime future = task(TaskType.template, false, null, null, RunPhase.running, at(30), 0L, null, null);
    TaskRunTime withNegativeAttempt =
        task(TaskType.template, false, null, null, RunPhase.completed, at(-40), 40_000L, null, List.of(negative));

    assertThat(CumulativeDuration.millis(clockSkew, NOW)).isZero();
    assertThat(CumulativeDuration.millis(future, NOW)).isZero();
    assertThat(CumulativeDuration.millis(withNegativeAttempt, NOW)).isEqualTo(40_000L);
  }

  // Parallel tasks each count: two tasks of a minute side by side are two minutes.
  @Test
  void tasksSideBySideEachCount() {
    assertThat(
            CumulativeDuration.millis(
                List.of(
                    task(TaskType.template, false, null, null, RunPhase.completed, at(-60), 60_000L, null, null),
                    task(TaskType.script, false, null, null, RunPhase.completed, at(-60), 60_000L, null, null),
                    task(TaskType.approval, false, null, null, RunPhase.completed, at(-600), 600_000L, null, null)),
                NOW))
        .isEqualTo(120_000L);
  }

  private static TaskRunTime completed(TaskType type, long duration) {
    return task(type, false, null, null, RunPhase.completed, at(-duration / 1000), duration, null, null);
  }

  private static TaskRunTime task(
      TaskType type,
      boolean foreach,
      String parentRef,
      String reusedFromRef,
      RunPhase phase,
      Date startTime,
      long duration,
      String statusReason,
      List<RunAttempt> attempts) {
    return new TaskRunTime(
        type, foreach, parentRef, reusedFromRef, phase, startTime, duration, statusReason, attempts);
  }

  private static RunAttempt attempt(Date claimedAt, Date endedAt, long duration, String reason) {
    RunAttempt attempt = new RunAttempt();
    attempt.setClaimedAt(claimedAt);
    attempt.setEndedAt(endedAt);
    attempt.setDuration(duration);
    attempt.setStatus(RunStatus.failed);
    attempt.setStatusReason(reason);
    return attempt;
  }

  private static Date at(long secondsFromNow) {
    return Date.from(NOW.plusSeconds(secondsFromNow));
  }
}
