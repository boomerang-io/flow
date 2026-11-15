package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Loki is read through the RestTemplate the service is given, never a client of its own, and its
 * query_range cursor is followed page by page until a page comes back short.
 */
class LokiLogServiceTest {

  private static final String UNABLE = "Unable to retrieve logs.";

  private RestTemplate template;
  private MockRestServiceServer loki;
  private LogService service;

  @BeforeEach
  void setUp() {
    template = new RestTemplate();
    loki = MockRestServiceServer.bindTo(template).build();
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("UNABLE_RETRIEVE_LOGS", Locale.getDefault(), UNABLE);
    service = new LogService();
    ReflectionTestUtils.setField(service, "loggingType", "loki");
    ReflectionTestUtils.setField(service, "lokiHost", "loki.test");
    ReflectionTestUtils.setField(service, "lokiPort", "3100");
    ReflectionTestUtils.setField(service, "messageSource", messages);
    ReflectionTestUtils.setField(service, "lokiRestTemplate", template);
  }

  @Test
  void aShortPageIsReadThroughTheGivenTemplateWithTheEncodedSelector() throws Exception {
    loki.expect(requestTo(startsWith("http://loki.test:3100/loki/api/v1/query_range?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(queryParam("limit", "5000"))
        .andExpect(queryParam("direction", "forward"))
        .andExpect(queryParam("start", "0"))
        .andExpect(
            queryParam(
                "query",
                "%7Bboomerang_io_taskrun_ref%3D%22tr-1%22%2Cboomerang_io_workflow_ref%3D%22wf-1%22%2Ccontainer%3D~%22task%7Cstep-task%22%7D"))
        .andRespond(withSuccess(page(1, 2), MediaType.APPLICATION_JSON));

    assertThat(stream()).isEqualTo("line-1\nline-2\n");
    loki.verify();
  }

  @Test
  void aFullPageContinuesFromItsLastTimestampAndSkipsTheRepeatedEntry() throws Exception {
    int full = LogService.LOKI_PAGE_LIMIT;
    loki.expect(queryParam("start", "0"))
        .andRespond(withSuccess(page(1, full), MediaType.APPLICATION_JSON));
    // Loki's start is inclusive: the second page opens on the first page's last entry.
    loki.expect(queryParam("start", String.valueOf(full)))
        .andRespond(withSuccess(page(full, full + 2), MediaType.APPLICATION_JSON));

    String log = stream();

    assertThat(log.lines()).hasSize(full + 2);
    assertThat(log).startsWith("line-1\n").endsWith("line-" + full + "\nline-" + (full + 1) + "\nline-" + (full + 2) + "\n");
    loki.verify();
  }

  @Test
  void noStreamForTheTaskAnswersTheUnableToRetrieveMessage() throws Exception {
    loki.expect(queryParam("start", "0"))
        .andRespond(
            withSuccess("{\"status\":\"success\",\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    assertThat(stream()).isEqualTo(UNABLE + System.lineSeparator());
    loki.verify();
  }

  private String stream() throws Exception {
    StreamingResponseBody body = service.streamLogForTask(null, "wf-1", "run-1", "tr-1");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  // A query_range answer with one stream whose entries are timestamped by their line number.
  private static String page(int from, int to) {
    StringBuilder values = new StringBuilder();
    for (int i = from; i <= to; i++) {
      if (i > from) {
        values.append(',');
      }
      values.append("[\"").append(i).append("\",\"line-").append(i).append("\\n\"]");
    }
    return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{},\"values\":["
        + values
        + "]}]}}";
  }
}
