package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.RestErrorResponse;
import java.io.IOException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

/**
 * The server refused or failed a call. {@link #error()} is the server's error body ({@code code},
 * {@code reason}, {@code message}, {@code status}); when the answer carried none - a bare 403, a
 * framework error - it holds the HTTP status alone and {@link #code()} is 0.
 */
public class FlowApiException extends RuntimeException {

  private final int status;
  private final RestErrorResponse error;

  public FlowApiException(int status, RestErrorResponse error) {
    super(describe(status, error));
    this.status = status;
    this.error = error;
  }

  /** The HTTP status of the answer. */
  public int status() {
    return status;
  }

  public RestErrorResponse error() {
    return error;
  }

  /** The server's error code, such as 1001; 0 when the answer carried no error body. */
  public int code() {
    return error.code();
  }

  /** The server's error reason, such as {@code QUERY_INVALID_FILTERS}; null without a body. */
  public String reason() {
    return error.reason();
  }

  static FlowApiException from(ClientHttpResponse response) throws IOException {
    int status = response.getStatusCode().value();
    byte[] body = StreamUtils.copyToByteArray(response.getBody());
    return new FlowApiException(status, parse(status, body));
  }

  // A body in the server's error shape is read; anything else keeps the status alone.
  private static RestErrorResponse parse(int status, byte[] body) {
    if (body.length > 0) {
      try {
        RestErrorResponse error = WireFormat.JSON.readValue(body, RestErrorResponse.class);
        if (error.reason() != null || error.code() != 0) {
          return error;
        }
      } catch (RuntimeException notTheErrorShape) {
        // Fall through to the status-only error.
      }
    }
    return new RestErrorResponse(null, 0, null, null, "HTTP " + status, null);
  }

  private static String describe(int status, RestErrorResponse error) {
    if (error.reason() == null) {
      return "HTTP " + status;
    }
    return error.reason()
        + " ("
        + error.code()
        + ")"
        + ((error.message() != null) ? ": " + error.message() : "");
  }
}
