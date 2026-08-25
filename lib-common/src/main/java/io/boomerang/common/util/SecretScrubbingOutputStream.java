package io.boomerang.common.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * An OutputStream that replaces every occurrence of the given secret values with
 * {@link DataAdapterUtil#REDACTED} before the bytes reach the wrapped stream. Used to filter the
 * TaskRun log stream on the workspace-scoped surface - the same sensitive-upward rule
 * DataAdapterUtil applies to run payloads.
 *
 * <p>Log output is line-oriented: bytes buffer until a newline, each complete line is scrubbed as
 * UTF-8 text and forwarded. A line that never ends (or the tail of the stream) is scrubbed and
 * forwarded on flush/close. A single line larger than the buffer cap is forwarded in scrubbed
 * chunks, retaining a {@code longestSecret - 1} byte tail between chunks: a secret wholly inside
 * the tail is caught, but one straddling a chunk boundary of such an oversize line can leak a
 * fragment - an accepted edge for 64KB+ single log lines.
 */
public class SecretScrubbingOutputStream extends OutputStream {

  private static final int MAX_BUFFERED_BYTES = 64 * 1024;

  private final OutputStream out;
  private final Set<String> secrets;
  private final int longestSecretBytes;
  private byte[] buffer = new byte[8 * 1024];
  private int length = 0;

  public SecretScrubbingOutputStream(OutputStream out, Set<String> secrets) {
    this.out = out;
    this.secrets = secrets;
    this.longestSecretBytes =
        secrets.stream()
            .mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length)
            .max()
            .orElse(0);
  }

  @Override
  public void write(int b) throws IOException {
    if (length == buffer.length) {
      if (length >= MAX_BUFFERED_BYTES) {
        spillLongLine();
      } else {
        byte[] grown = new byte[Math.min(buffer.length * 2, MAX_BUFFERED_BYTES)];
        System.arraycopy(buffer, 0, grown, 0, length);
        buffer = grown;
      }
    }
    buffer[length++] = (byte) b;
    if (b == '\n') {
      scrubAndForward(length);
    }
  }

  @Override
  public void write(byte[] bytes, int off, int len) throws IOException {
    for (int i = off; i < off + len; i++) {
      write(bytes[i]);
    }
  }

  @Override
  public void flush() throws IOException {
    scrubAndForward(length);
    out.flush();
  }

  @Override
  public void close() throws IOException {
    scrubAndForward(length);
    out.close();
  }

  // Forward the first n buffered bytes, scrubbed.
  private void scrubAndForward(int n) throws IOException {
    if (n == 0) {
      return;
    }
    String text = new String(buffer, 0, n, StandardCharsets.UTF_8);
    for (String secret : secrets) {
      text = text.replace(secret, DataAdapterUtil.REDACTED);
    }
    out.write(text.getBytes(StandardCharsets.UTF_8));
    System.arraycopy(buffer, n, buffer, 0, length - n);
    length -= n;
  }

  // A line larger than the cap: forward the scrubbed head, keeping a tail so a secret that is
  // wholly within the retained bytes is caught on the next pass (see the class doc for the
  // straddling-fragment edge).
  private void spillLongLine() throws IOException {
    int keep = Math.max(longestSecretBytes - 1, 0);
    scrubAndForward(Math.max(length - keep, 1));
  }
}
