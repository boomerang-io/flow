package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Unit tests for {@link KubeLogService#getPodLog(InputStream, String)} — the {@code
 * InputStream.transferTo} copy loop that replaced the manual {@code 1KB}-buffer {@code while
 * ((nRead = ...read(data)) > 0)} loop. No Spring context / Kubernetes mock server needed: the
 * method under test only touches the InputStream/OutputStream it's handed.
 */
public class KubeLogServiceTest {

  @Test
  public void testGetPodLogStreamsAllBytes() throws Exception {
    KubeLogService service = new KubeLogService(null, null);
    // Larger than the old 1KB read buffer, to prove multi-chunk transfers aren't truncated.
    byte[] payload = "line one\nline two\n".repeat(200).getBytes();
    ByteArrayInputStream input = new ByteArrayInputStream(payload);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    StreamingResponseBody body = service.getPodLog(input, "pod-1");
    body.writeTo(output);

    assertArrayEquals(payload, output.toByteArray());
  }

  @Test
  public void testGetPodLogDoesNotStopEarlyOnALegalZeroLengthRead() throws Exception {
    // InputStream#read(byte[]) is legally allowed to return 0 without reaching end-of-stream. The
    // old `while ((nRead = inputStream.read(data)) > 0)` loop treated 0 as "done" and would have
    // silently dropped the remaining bytes; transferTo loops correctly until -1.
    byte[] payload = "abcdef".getBytes();
    InputStream zeroThenData =
        new InputStream() {
          private int calls = 0;
          private final ByteArrayInputStream delegate = new ByteArrayInputStream(payload);

          @Override
          public int read() {
            return delegate.read();
          }

          @Override
          public int read(byte[] b, int off, int len) {
            calls++;
            if (calls == 1) {
              return 0;
            }
            return delegate.read(b, off, len);
          }
        };

    KubeLogService service = new KubeLogService(null, null);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    service.getPodLog(zeroThenData, "pod-2").writeTo(output);

    assertArrayEquals(payload, output.toByteArray());
  }
}
