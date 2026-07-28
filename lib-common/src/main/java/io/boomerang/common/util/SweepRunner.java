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
}
