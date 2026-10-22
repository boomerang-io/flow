package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskContextTest {

  private final TaskContext context = new TaskContext(new TaskRun(), "d-1", null);

  @Test
  void aCancelActionRunsOnceWhenTheTaskIsTerminated() {
    AtomicInteger runs = new AtomicInteger();
    context.onCancel(runs::incrementAndGet);

    context.cancel();
    context.cancel();

    assertThat(context.isCancelled()).isTrue();
    assertThat(runs).hasValue(1);
  }

  @Test
  void aCancelActionAddedAfterTerminationRunsAtOnce() {
    AtomicInteger runs = new AtomicInteger();
    context.cancel();

    context.onCancel(runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  void aFailingCancelActionDoesNotStopTheOthers() {
    AtomicInteger runs = new AtomicInteger();
    context.onCancel(
        () -> {
          throw new IllegalStateException("boom");
        });
    context.onCancel(runs::incrementAndGet);

    context.cancel();

    assertThat(runs).hasValue(1);
  }

  @Test
  void aContextStartsNotCancelled() {
    assertThat(context.isCancelled()).isFalse();
    assertThat(context.dispatcherId()).isEqualTo("d-1");
  }

  @Test
  void aContextWithoutAnEngineStoresNoLog() {
    assertThat(context.uploadLog("some output")).isFalse();
    assertThat(context.uploadLog((String) null)).isFalse();
  }

  @Test
  void aLogIsSentToTheEngineForTheTask() {
    FakeEngine engine = new FakeEngine();
    TaskRun task = new TaskRun();
    task.setId("task-9");
    TaskContext withEngine =
        new TaskContext(task, "d-1", null, new LogUploader(engine, () -> "d-1"));

    assertThat(withEngine.uploadLog("line 1\nline 2\n")).isTrue();

    assertThat(engine.logs).containsEntry("task-9", "line 1\nline 2\n");
  }
}
