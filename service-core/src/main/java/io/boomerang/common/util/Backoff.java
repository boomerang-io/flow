package io.boomerang.common.util;

import io.boomerang.common.enums.BackoffType;
import io.boomerang.common.model.RetryBackoff;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: 10s base, doubling per attempt, capped at a 5m ceiling, plus up
 * to 5s of jitter. Returns the absolute time the next attempt becomes eligible. A task node's own
 * {@link RetryBackoff} replaces the base and the ceiling, or holds the wait fixed.
 */
public final class Backoff {

  private static final long BASE_MILLIS = 10000L;
  private static final long CEILING_MILLIS = 300000L;
  private static final long JITTER_MILLIS = 5000L;

  private Backoff() {}

  public static Date nextRetryAt(int attempts) {
    return nextRetryAt(null, attempts);
  }

  public static Date nextRetryAt(RetryBackoff policy, int attempts) {
    long base = seconds(policy != null ? policy.getInitial() : null, BASE_MILLIS);
    long ceiling = seconds(policy != null ? policy.getMax() : null, CEILING_MILLIS);
    long backoff =
        (policy != null && BackoffType.fixed.equals(policy.getType()))
            ? base
            : Math.min(base * (1L << Math.min(attempts, 30)), Math.max(ceiling, base));
    long jitter = ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
    return new Date(System.currentTimeMillis() + backoff + jitter);
  }

  private static long seconds(Long value, long defaultMillis) {
    return (value != null && value > 0) ? value * 1000L : defaultMillis;
  }
}
