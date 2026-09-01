package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A19: {@link LogClient} previously built its query string via raw {@code key + "=" + value}
 * concatenation with no encoding, so a value containing {@code &}, {@code =}, or a space would
 * corrupt the query string (inject extra params / truncate values). These tests pin the
 * {@link UriComponentsBuilder}-based replacement.
 */
class LogClientTest {

  private LogClient logClient;

  @BeforeEach
  void setUp() throws Exception {
    logClient = new LogClient();
    Field urlField = LogClient.class.getDeclaredField("logStreamURL");
    urlField.setAccessible(true);
    urlField.set(logClient, "http://service-dispatcher/api/v1/dispatcher/taskrun/log");
  }

  @Test
  void encodesAmpersandEqualsAndSpaceInParamValues() {
    URI uri =
        logClient.buildLogStreamUri("wf id", "run&ref", "task=run");

    String query = uri.getRawQuery();
    // Exactly three top-level params must survive - not more, which would indicate an injected
    // param from an unescaped '&' in a value.
    assertThat(query.split("&")).hasSize(3);
    assertThat(query).contains("workflowRef=wf%20id");
    assertThat(query).contains("workflowRunRef=run%26ref");
    assertThat(query).contains("taskRunRef=task%3Drun");
    // The raw, unencoded separators must not leak into the query string.
    assertThat(query).doesNotContain("run&ref");
    assertThat(query).doesNotContain("task=run");
  }

  @Test
  void preservesParamNamesAndBaseUrlForSimpleValues() {
    URI uri = logClient.buildLogStreamUri("workflow-1", "run-1", "taskrun-1");

    assertThat(uri.toString())
        .isEqualTo(
            "http://service-dispatcher/api/v1/dispatcher/taskrun/log"
                + "?workflowRef=workflow-1&workflowRunRef=run-1&taskRunRef=taskrun-1");
  }
}
