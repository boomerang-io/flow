package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.FilterValuesOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The log-stream half of the sensitive-upward rule: see WorkspaceTaskRunService.streamLog. */
class FilterValuesOutputStreamTest {

  private static final String SECRET = "ghp_secret42";

  private static String scrubbed(Set<String> secrets, String... writes) throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (FilterValuesOutputStream stream = new FilterValuesOutputStream(sink, secrets)) {
      for (String write : writes) {
        stream.write(write.getBytes(StandardCharsets.UTF_8));
      }
    }
    return sink.toString(StandardCharsets.UTF_8);
  }

  @Test
  void scrubsASecretWithinALine() throws IOException {
    assertEquals(
        "token=" + DataAdapterUtil.REDACTED + " used\n",
        scrubbed(Set.of(SECRET), "token=" + SECRET + " used\n"));
  }

  @Test
  void scrubsASecretSplitAcrossWrites() throws IOException {
    // Chunked transfer can split anywhere; buffering to the newline must reassemble it.
    assertEquals(
        "auth: " + DataAdapterUtil.REDACTED + "\nok\n",
        scrubbed(Set.of(SECRET), "auth: ghp_sec", "ret42\nok\n"));
  }

  @Test
  void scrubsTheUnterminatedTailOnClose() throws IOException {
    assertEquals(
        "last line " + DataAdapterUtil.REDACTED,
        scrubbed(Set.of(SECRET), "last line " + SECRET));
  }

  @Test
  void passesUntaintedOutputThroughUnchanged() throws IOException {
    assertEquals("plain log\nlines\n", scrubbed(Set.of(SECRET), "plain log\nlines\n"));
  }

  @Test
  void scrubsMultipleSecrets() throws IOException {
    assertEquals(
        DataAdapterUtil.REDACTED + " and " + DataAdapterUtil.REDACTED + "\n",
        scrubbed(Set.of(SECRET, "s3cond"), SECRET + " and s3cond\n"));
  }
}
