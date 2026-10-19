package io.boomerang.common.util;

import jakarta.servlet.http.HttpServletResponse;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * A response body whose {@link #flush()} reaches the network. Spring hands a {@link
 * StreamingResponseBody} a stream whose own flush does nothing ({@code ServletServerHttpResponse}
 * unless {@code spring.http.response.flush.enabled} is set), so a live log written through it would
 * sit in the servlet buffer until the task ended. This stream flushes the servlet response instead,
 * for the routes that stream as they are written.
 */
public final class ResponseFlushingOutputStream extends FilterOutputStream {

  private final HttpServletResponse response;

  public ResponseFlushingOutputStream(OutputStream body, HttpServletResponse response) {
    super(body);
    this.response = response;
  }

  /** Wrap a body so every flush it makes is a flush of the servlet response. */
  public static StreamingResponseBody flushing(
      StreamingResponseBody body, HttpServletResponse response) {
    return out -> body.writeTo(new ResponseFlushingOutputStream(out, response));
  }

  @Override
  public void write(byte[] bytes, int off, int len) throws IOException {
    out.write(bytes, off, len);
  }

  @Override
  public void flush() throws IOException {
    response.flushBuffer();
  }

  // Spring owns the underlying stream and closes it when the body returns.
  @Override
  public void close() throws IOException {
    flush();
  }
}
