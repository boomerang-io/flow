package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** An error answer is the server's error, in its own shape, on every route. */
class FlowApiExceptionTest {

  private static final String RUN = StubServer.API + "/workspace/ws/workflowrun/r1?withTasks=true";

  private final StubServer stub = new StubServer();

  @Test
  void theServersErrorBodyIsCarriedWithItsCodeReasonMessageAndStatus() {
    stub.server
        .expect(requestTo(RUN))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"timestamp\":\"2026-10-01T10:00:00.000+00:00\",\"code\":1301,"
                        + "\"reason\":\"WORKFLOWRUN_INVALID_REF\",\"message\":\"No run r1.\","
                        + "\"status\":\"404 NOT_FOUND\"}"));

    assertThatThrownBy(() -> stub.flow.runs("ws").get("r1"))
        .isInstanceOf(FlowApiException.class)
        .hasMessage("WORKFLOWRUN_INVALID_REF (1301): No run r1.")
        .satisfies(
            thrown -> {
              FlowApiException error = (FlowApiException) thrown;
              assertThat(error.status()).isEqualTo(404);
              assertThat(error.code()).isEqualTo(1301);
              assertThat(error.reason()).isEqualTo("WORKFLOWRUN_INVALID_REF");
              assertThat(error.error().message()).isEqualTo("No run r1.");
              assertThat(error.error().status()).isEqualTo("404 NOT_FOUND");
              assertThat(error.error().timestamp()).isNotNull();
            });
  }

  @Test
  void anAnswerWithoutTheErrorShapeKeepsTheStatusAlone() {
    stub.server.expect(requestTo(RUN)).andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> stub.flow.runs("ws").get("r1"))
        .isInstanceOf(FlowApiException.class)
        .hasMessage("HTTP 403")
        .satisfies(
            thrown -> {
              FlowApiException error = (FlowApiException) thrown;
              assertThat(error.status()).isEqualTo(403);
              assertThat(error.code()).isZero();
              assertThat(error.reason()).isNull();
              assertThat(error.error().status()).isEqualTo("HTTP 403");
            });
  }

  @Test
  void aProblemDetailAnswerKeepsTheStatusAlone() {
    stub.server
        .expect(requestTo(RUN))
        .andRespond(
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                    "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\","
                        + "\"status\":500}"));

    assertThatThrownBy(() -> stub.flow.runs("ws").get("r1"))
        .isInstanceOf(FlowApiException.class)
        .hasMessage("HTTP 500");
  }
}
