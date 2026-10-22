package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** One task's buffer: lines land in order, head and tail survive a flood, the gap is said. */
class TaskLogCaptureTest {

  @AfterEach
  void untag() {
    MDC.remove(TaskLogCapture.MDC_KEY);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Test
  void linesLandInOrderWithANewlineEach() {
    TaskLogCapture capture = new TaskLogCapture();
    capture.open("t-1");

    capture.append("t-1", "one");
    capture.append("t-1", "two\n");
    capture.append("t-1", "three");

    assertThat(text(capture.snapshot("t-1"))).isEqualTo("one\ntwo\nthree\n");
    assertThat(text(capture.close("t-1"))).isEqualTo("one\ntwo\nthree\n");
    assertThat(capture.isCapturing("t-1")).isFalse();
  }

  @Test
  void aFloodKeepsTheHeadAndTheTailAndSaysHowMuchFellBetween() {
    // 10-byte lines ("line-NN\n" is 8; pad to 10) against 30-byte caps: three at each end.
    TaskLogCapture capture = new TaskLogCapture(30, 30);
    capture.open("t-1");

    for (int i = 0; i < 20; i++) {
      capture.append("t-1", String.format("line-%02d  ", i));
    }

    String log = text(capture.close("t-1"));
    assertThat(log)
        .isEqualTo(
            "line-00  \nline-01  \nline-02  \n"
                + "... 140 bytes dropped ...\n"
                + "line-17  \nline-18  \nline-19  \n");
  }

  @Test
  void aLineThatDoesNotFitTheHeadClosesItForGood() {
    // Head 10, tail 100: the 13-byte second line starts the tail; the short one after stays there.
    TaskLogCapture capture = new TaskLogCapture(10, 100);
    capture.open("t-1");

    capture.append("t-1", "ab");
    capture.append("t-1", "twelve-bytes");
    capture.append("t-1", "c");

    assertThat(text(capture.close("t-1"))).isEqualTo("ab\ntwelve-bytes\nc\n");
  }

  @Test
  void aLineForATaskNotOpenHereIsDropped() {
    TaskLogCapture capture = new TaskLogCapture();
    capture.open("t-1");

    capture.append("t-2", "elsewhere");
    capture.append(null, "untagged");
    capture.append("t-1", null);

    assertThat(capture.snapshot("t-1")).isEmpty();
    assertThat(capture.snapshot("t-2")).isEmpty();
  }

  @Test
  void openTagsTheThreadAndCloseUntagsIt() {
    TaskLogCapture capture = new TaskLogCapture();

    capture.open("t-1");
    assertThat(MDC.get(TaskLogCapture.MDC_KEY)).isEqualTo("t-1");

    capture.close("t-1");
    assertThat(MDC.get(TaskLogCapture.MDC_KEY)).isNull();
  }

  @Test
  void theCapsMustBePositive() {
    assertThatThrownBy(() -> new TaskLogCapture(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaskLogCapture(1, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
