package io.boomerang.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.BackoffType;
import io.boomerang.common.model.RetryBackoff;
import org.junit.jupiter.api.Test;

/**
 * The engine's backoff doubles from 10 s to a 5 min cap; a task node's own setting replaces the
 * base and the cap, or holds the wait fixed. Every wait carries up to 5 s of jitter.
 */
class BackoffTest {

  private static long waitMillis(RetryBackoff policy, int attempts) {
    return Backoff.nextRetryAt(policy, attempts).getTime() - System.currentTimeMillis();
  }

  @Test
  void theEngineDefaultDoublesFromTenSecondsToFiveMinutes() {
    assertThat(waitMillis(null, 0)).isBetween(9_000L, 16_000L);
    assertThat(waitMillis(null, 2)).isBetween(39_000L, 46_000L);
    assertThat(waitMillis(null, 10)).isBetween(299_000L, 306_000L);
  }

  @Test
  void aFixedPolicyWaitsItsInitialEveryTime() {
    RetryBackoff policy = new RetryBackoff();
    policy.setType(BackoffType.fixed);
    policy.setInitial(30L);

    assertThat(waitMillis(policy, 0)).isBetween(29_000L, 36_000L);
    assertThat(waitMillis(policy, 5)).isBetween(29_000L, 36_000L);
  }

  @Test
  void anExponentialPolicyDoublesFromItsInitialToItsMax() {
    RetryBackoff policy = new RetryBackoff();
    policy.setType(BackoffType.exponential);
    policy.setInitial(2L);
    policy.setMax(5L);

    assertThat(waitMillis(policy, 0)).isBetween(1_000L, 8_000L);
    assertThat(waitMillis(policy, 1)).isBetween(3_000L, 10_000L);
    assertThat(waitMillis(policy, 4)).isBetween(4_000L, 11_000L);
  }

  @Test
  void aPolicyWithNoFieldsIsTheEngineDefault() {
    assertThat(waitMillis(new RetryBackoff(), 1)).isBetween(19_000L, 26_000L);
  }
}
