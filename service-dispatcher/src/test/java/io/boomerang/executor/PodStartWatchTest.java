package io.boomerang.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.error.TaskExecutionException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PodStartWatchTest {

  private static final Instant CREATED = Instant.parse("2026-10-05T00:00:00Z");
  private static final Duration DEADLINE = Duration.ofMinutes(15);
  private static final Duration GRACE = Duration.ofSeconds(60);

  private final PodStartWatch watch = new PodStartWatch(CREATED, DEADLINE, GRACE);

  private static Instant at(long seconds) {
    return CREATED.plusSeconds(seconds);
  }

  @Test
  void aPodThatCannotPullItsImageFailsOnceTheGraceHasPassed() {
    assertThat(watch.check(PodStart.pending("ErrImagePull", "task: not found"), at(10))).isNull();

    TaskExecutionException failure =
        watch.check(PodStart.pending("ImagePullBackOff", "task: back-off"), at(70));

    assertThat(failure.getStatusReason()).isEqualTo("ImagePull");
    assertThat(failure.getMessage()).contains("ImagePullBackOff");
  }

  @Test
  void theGraceRunsFromTheFirstFailureAsKubernetesAlternatesTheReason() {
    watch.check(PodStart.pending("ErrImagePull", "m"), at(10));
    watch.check(PodStart.pending("ImagePullBackOff", "m"), at(40));

    assertThat(watch.check(PodStart.pending("ErrImagePull", "m"), at(71))).isNotNull();
  }

  @Test
  void aFailureThatClearsStartsTheGraceAgain() {
    watch.check(PodStart.pending("ErrImagePull", "m"), at(10));
    watch.check(PodStart.pending(null, "the pod is pending"), at(40));

    assertThat(watch.check(PodStart.pending("ErrImagePull", "m"), at(80))).isNull();
  }

  @Test
  void aContainerThatCannotBeCreatedFailsAsADispatchError() {
    watch.check(PodStart.pending("CreateContainerConfigError", "secret missing"), at(0));

    TaskExecutionException failure =
        watch.check(PodStart.pending("CreateContainerConfigError", "secret missing"), at(60));

    assertThat(failure.getStatusReason()).isEqualTo("DispatchError");
  }

  @Test
  void aPodStillPendingAtTheDeadlineIsAStartTimeout() {
    assertThat(watch.check(PodStart.notCreated(), at(899))).isNull();

    TaskExecutionException failure = watch.check(PodStart.notCreated(), at(900));

    assertThat(failure.getStatusReason()).isEqualTo("StartTimeout");
    assertThat(failure.getMessage()).contains("15 minutes").contains("no pod was created");
  }

  @Test
  void onceStartedNothingIsCheckedAgain() {
    watch.check(PodStart.running(), at(5));

    assertThat(watch.hasStarted()).isTrue();
    assertThat(watch.check(PodStart.pending("ErrImagePull", "m"), at(2000))).isNull();
  }

  @Test
  void aZeroDeadlineTurnsTheStartTimeoutOff() {
    PodStartWatch noDeadline = new PodStartWatch(CREATED, Duration.ZERO, GRACE);

    assertThat(noDeadline.check(PodStart.notCreated(), at(100_000))).isNull();
  }
}
