package io.boomerang.dispatcher.sdk;

import java.time.Duration;

/** The wait between attempts at a call the engine must eventually take: doubling, capped. */
record Backoff(Duration initial, Duration max) {

  static final Backoff DEFAULT = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));

  Duration next(Duration current) {
    Duration doubled = current.multipliedBy(2);
    return (doubled.compareTo(max) < 0) ? doubled : max;
  }

  /** Sleep for {@code delay}; false when the thread was interrupted, which ends the retrying. */
  static boolean sleep(Duration delay) {
    try {
      Thread.sleep(delay);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
