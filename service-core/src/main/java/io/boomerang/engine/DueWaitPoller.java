package io.boomerang.engine;

import io.boomerang.common.util.SweepRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Resume due waits - a sleep that has run its duration, an acquirelock retry - about once a
 * second, so they end on time rather than at the next 30 s watcher sweep. One query on the sparse
 * {@code waitUntil} index per pass, on every instance; the claim in {@link
 * TaskRunService#tryStartWaitingResume} lets only one of them resume each wait.
 *
 * <p>It polls on a virtual thread of its own: the watcher sweeps and the retention purge are
 * fixed-delay jobs, which Spring runs on its single scheduler thread, so a wait polled there would
 * wait behind them.
 */
@Component
public class DueWaitPoller implements SmartLifecycle {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowWatcher watcher;
  private final boolean enabled;
  private final long intervalMillis;
  private volatile Thread thread;

  public DueWaitPoller(
      WorkflowWatcher watcher,
      @Value("${flow.watcher.enabled:true}") boolean enabled,
      @Value("${flow.watcher.wait-interval-ms:1000}") long intervalMillis) {
    this.watcher = watcher;
    this.enabled = enabled;
    this.intervalMillis = intervalMillis;
  }

  @Override
  public void start() {
    if (enabled && thread == null) {
      thread = Thread.ofVirtual().name("due-wait-poller").start(this::poll);
    }
  }

  @Override
  public void stop() {
    Thread running = thread;
    thread = null;
    if (running != null) {
      running.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return thread != null;
  }

  private void poll() {
    while (thread == Thread.currentThread()) {
      try {
        Thread.sleep(intervalMillis);
      } catch (InterruptedException e) {
        return;
      }
      SweepRunner.runIsolated(
          "resumeDueWaitingTasks",
          watcher::resumeDueWaitingTasks,
          (sweep, ex) -> LOGGER.error("Sweep {} failed; the next pass tries again.", sweep, ex));
    }
  }
}
