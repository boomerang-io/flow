package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/**
 * The poller's thread can run before {@code start} returns. Every start must still poll: a poller
 * that exits on its first check leaves every due wait on that instance waiting for good, while
 * {@code isRunning} reports true.
 */
class DueWaitPollerTest {

  @Test
  void everyStartPolls() {
    for (int start = 0; start < 200; start++) {
      WorkflowWatcher watcher = mock(WorkflowWatcher.class);
      DueWaitPoller poller = new DueWaitPoller(watcher, true, 1);
      poller.start();
      try {
        verify(watcher, timeout(2000).atLeast(2)).resumeDueWaitingTasks();
      } finally {
        poller.stop();
      }
    }
  }

  @Test
  void stopEndsThePoller() {
    DueWaitPoller poller = new DueWaitPoller(mock(WorkflowWatcher.class), true, 1);
    poller.start();
    poller.stop();
    assertThat(poller.isRunning()).isFalse();
  }
}
