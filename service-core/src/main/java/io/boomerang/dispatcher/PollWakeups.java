package io.boomerang.dispatcher;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The monitor every held poll on this engine instance waits on between re-checks. Admitting a
 * task, or a run that waits to be provisioned, signals it, so a held poll looks at the claimable
 * page again at once instead of at its next timed re-check; the timed wait stays, as an admission
 * on another instance signals only that instance's polls. A poll reads the generation before its
 * query and waits for a change from it, so a signal that lands during the query is not lost. The
 * lock never pins a virtual thread, which the polls run on.
 */
@Component
public class PollWakeups {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition woken = lock.newCondition();
  private long generation;

  /** The count of signals so far, to wait for a change from. */
  public long generation() {
    lock.lock();
    try {
      return generation;
    } finally {
      lock.unlock();
    }
  }

  /** Wait until the generation differs from {@code seen} or the interval passes. */
  public void awaitChange(long seen, Duration interval) throws InterruptedException {
    long remaining = interval.toNanos();
    lock.lockInterruptibly();
    try {
      while (generation == seen && remaining > 0) {
        remaining = woken.awaitNanos(remaining);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Wake every held poll. */
  public void signal() {
    lock.lock();
    try {
      generation++;
      woken.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
