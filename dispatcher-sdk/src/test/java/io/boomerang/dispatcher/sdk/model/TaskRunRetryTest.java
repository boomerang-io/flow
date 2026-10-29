package io.boomerang.dispatcher.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The last-attempt reading matches the engine's requeue rule: a reported failure is retried while
 * {@code failures < max}, so a node asking for {@code max} retries gets {@code max + 1} attempts
 * and the attempt dispatched with {@code failures == max} is the last.
 */
class TaskRunRetryTest {

  @Test
  void aNodeWithNoRetryIsAlwaysOnItsLastAttempt() {
    assertThat(retry(null, 0).isLastAttempt()).isTrue();
    assertThat(retry(0, 0).isLastAttempt()).isTrue();
    TaskRun order = new TaskRun();
    assertThat(order.isLastAttempt()).as("no retry on the order at all").isTrue();
  }

  @Test
  void theLastAttemptIsTheOneDispatchedWithFailuresEqualToMax() {
    assertThat(retry(1, 0).isLastAttempt()).as("max 1: the engine still retries").isFalse();
    assertThat(retry(1, 1).isLastAttempt()).isTrue();
    assertThat(retry(2, 0).isLastAttempt()).isFalse();
    assertThat(retry(2, 1).isLastAttempt()).as("max 2: one retry left").isFalse();
    assertThat(retry(2, 2).isLastAttempt()).isTrue();
    TaskRun order = new TaskRun();
    order.setRetry(retry(2, 1));
    assertThat(order.isLastAttempt()).isFalse();
  }

  private static TaskRunRetry retry(Integer max, Integer failures) {
    TaskRunRetry retry = new TaskRunRetry();
    retry.setMax(max);
    retry.setFailures(failures);
    return retry;
  }
}
