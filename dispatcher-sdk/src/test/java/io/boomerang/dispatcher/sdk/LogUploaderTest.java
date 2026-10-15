package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * A finished log is sent best effort: a refusal never reaches the handler, and an engine that
 * keeps no logs is asked once.
 */
class LogUploaderTest {

  private final FakeEngine engine = new FakeEngine();
  private final LogUploader uploader = new LogUploader(engine, () -> "d-1");

  private static TaskRun task(String id) {
    TaskRun task = new TaskRun();
    task.setId(id);
    return task;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aStoredLogIsReportedStored() {
    assertThat(uploader.upload(task("t-1"), bytes("ok"))).isTrue();
    assertThat(engine.logs).containsEntry("t-1", "ok");
  }

  @Test
  void anEmptyLogIsNotSent() {
    assertThat(uploader.upload(task("t-1"), new byte[0])).isFalse();
    assertThat(uploader.upload(task("t-1"), null)).isFalse();
    assertThat(engine.logs).isEmpty();
  }

  @Test
  void anEngineWithNoArtifactStoreIsAskedOnce() {
    engine.logAnswers.add(
        () -> {
          throw FakeEngine.noArtifactStore();
        });

    assertThat(uploader.upload(task("t-1"), bytes("first"))).isFalse();
    assertThat(uploader.isUnsupported()).isTrue();
    assertThat(uploader.upload(task("t-2"), bytes("second"))).isFalse();

    // The first was sent and refused; the second never left.
    assertThat(engine.logs).isEmpty();
    assertThat(engine.logAnswers).isEmpty();
  }

  @Test
  void aRefusalForOneLogDoesNotStopTheNext() {
    engine.logAnswers.add(
        () -> {
          throw FakeEngine.refused(HttpStatus.CONTENT_TOO_LARGE);
        });

    assertThat(uploader.upload(task("t-1"), bytes("too big"))).isFalse();
    assertThat(uploader.isUnsupported()).isFalse();
    assertThat(uploader.upload(task("t-2"), bytes("fine"))).isTrue();
    // The refused one was never stored; the next was.
    assertThat(engine.logs).containsOnlyKeys("t-2");
  }

  @Test
  void anUnreachableEngineIsATransientMiss() {
    engine.logAnswers.add(
        () -> {
          throw FakeEngine.unreachable();
        });

    assertThat(uploader.upload(task("t-1"), bytes("x"))).isFalse();
    assertThat(uploader.isUnsupported()).isFalse();
  }
}
