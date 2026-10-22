package io.boomerang.common.util;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared scaffolding for the level-triggered sweeps: iterate a page and run the per-item action,
 * isolating each item's failure so one bad item never aborts the rest of the page. Framework
 * agnostic - the caller supplies the action and the failure handler (typically a log line).
 */
public final class SweepRunner {

  private SweepRunner() {}

  public static <T> void forEachIsolated(
      List<T> page, Consumer<T> action, BiConsumer<T, Exception> onFailure) {
    for (T item : page) {
      try {
        action.accept(item);
      } catch (Exception ex) {
        onFailure.accept(item, ex);
      }
    }
  }

  /**
   * Isolate a whole sweep, so one sweep's failure never stops the sweeps after it. {@link
   * #forEachIsolated} only guards the per-item action - the query that produces the page runs
   * outside it, and those queries carry a server-side time limit that throws rather than returning
   * a short page. Without this, a single slow query would silently stop every later sweep in the
   * cycle, including the timeout reaps.
   */
  public static void runIsolated(String name, Runnable sweep, BiConsumer<String, Exception> onFailure) {
    try {
      sweep.run();
    } catch (Exception ex) {
      onFailure.accept(name, ex);
    }
  }
}
