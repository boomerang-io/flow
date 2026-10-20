package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The live log route flushes the servlet response each time the pod log flushes, so the engine
 * reads a running pod's lines as they are written instead of when Spring's body stream completes.
 */
class LogStreamFlushTest {

  @Test
  void eachFlushOfThePodLogFlushesTheServletResponse() throws Exception {
    StreamingResponseBody podLog =
        out -> {
          out.write("tick-1\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
          out.write("tick-2\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
        };
    LogService logService = mock(LogService.class);
    when(logService.streamLogForTask(any(), eq("wf-1"), eq("run-1"), eq("task-1"))).thenReturn(podLog);
    LogV1Controller controller = new LogV1Controller();
    ReflectionTestUtils.setField(controller, "logService", logService);

    ByteArrayOutputStream received = new ByteArrayOutputStream();
    List<String> seenAtEachResponseFlush = new ArrayList<>();
    HttpServletResponse response = mock(HttpServletResponse.class);
    doAnswer(
            invocation -> {
              seenAtEachResponseFlush.add(received.toString(StandardCharsets.UTF_8));
              return null;
            })
        .when(response)
        .flushBuffer();

    ResponseEntity<StreamingResponseBody> answer =
        controller.streamLogForTask(response, "wf-1", "run-1", "task-1");
    // What Spring hands the body: a stream whose own flush does nothing.
    answer.getBody().writeTo(StreamUtils.nonFlushing(received));

    assertThat(seenAtEachResponseFlush).containsExactly("tick-1\n", "tick-1\ntick-2\n");
  }
}
