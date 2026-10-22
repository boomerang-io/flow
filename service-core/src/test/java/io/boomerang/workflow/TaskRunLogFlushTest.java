package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The task-run log route flushes the servlet response each time the log flushes, so a running
 * task's lines reach the reader as they arrive instead of when Spring's body stream completes.
 */
class TaskRunLogFlushTest {

  @Test
  void eachFlushOfTheLogFlushesTheServletResponse() throws Exception {
    WorkflowRunService workflowRunService = mock(WorkflowRunService.class);
    StreamingResponseBody log =
        out -> {
          out.write("tick-1\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
          out.write("tick-2\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
        };
    when(workflowRunService.streamTaskRunLog("taskrun-1")).thenReturn(log);

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
        new TaskRunControllerV2(workflowRunService).streamTaskRunLog("taskrun-1", response);
    // What Spring hands the body: a stream whose own flush does nothing.
    answer.getBody().writeTo(StreamUtils.nonFlushing(received));

    assertThat(seenAtEachResponseFlush).containsExactly("tick-1\n", "tick-1\ntick-2\n");
  }
}
