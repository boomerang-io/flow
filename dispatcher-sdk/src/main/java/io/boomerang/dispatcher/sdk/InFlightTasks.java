package io.boomerang.dispatcher.sdk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * The task runs one poller has taken to run and not yet seen ended. A task is in flight from the
 * moment its claim arrives until the engine has accepted or refused its end, so it holds capacity
 * and its lease is renewed for that whole time. Termination orders are never in flight.
 */
public final class InFlightTasks {

  private final IntSupplier maxInFlight;

  private final Set<String> ids = ConcurrentHashMap.newKeySet();

  private volatile Runnable onChange = () -> {};

  /**
   * Cap the tasks in flight at {@code maxInFlight}, read on every poll so it can change at
   * runtime; a value of 0 or less is no cap.
   */
  public InFlightTasks(IntSupplier maxInFlight) {
    this.maxInFlight = maxInFlight;
  }

  /** Return how many more task runs the next poll may claim, or null when there is no cap. */
  public Integer free() {
    int max = maxInFlight.getAsInt();
    return (max > 0) ? Math.max(0, max - ids.size()) : null;
  }

  /** Return whether the cap is reached; never with no cap. */
  boolean isFull() {
    Integer free = free();
    return free != null && free == 0;
  }

  /** Call {@code onChange} each time a task enters or leaves flight. */
  void onChange(Runnable onChange) {
    this.onChange = onChange;
  }

  /** Take {@code taskRunId} into flight; false when it already is. */
  boolean add(String taskRunId) {
    boolean added = ids.add(taskRunId);
    if (added) {
      onChange.run();
    }
    return added;
  }

  void remove(String taskRunId) {
    if (ids.remove(taskRunId)) {
      onChange.run();
    }
  }

  public boolean contains(String taskRunId) {
    return ids.contains(taskRunId);
  }

  /** Return the ids in flight now, for the lease heartbeat. */
  public List<String> ids() {
    return List.copyOf(ids);
  }

  public int size() {
    return ids.size();
  }
}
