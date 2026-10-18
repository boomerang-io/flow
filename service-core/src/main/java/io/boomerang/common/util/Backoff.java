package io.boomerang.common.util;

import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: 10s base, doubling per attempt, capped at a 5m ceiling, plus up
 * to 5s of jitter. Returns the absolute time the next attempt becomes eligible.
 */
public final class Backoff {

  private static final long BASE_MILLIS = 10000L;
  private static final long CEILING_MILLIS = 300000L;
  private static final long JITTER_MILLIS = 5000L;

  private Backoff() {}

  public static Date nextRetryAt(int attempts) {
    long backoff = Math.min(BASE_MILLIS * (1L << Math.min(attempts, 30)), CEILING_MILLIS);
    long jitter = ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
    return new Date(System.currentTimeMillis() + backoff + jitter);
  }
}
