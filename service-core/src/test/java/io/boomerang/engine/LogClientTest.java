package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.RunClaim;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Where the engine reads a task's live log: at the URL the claim's dispatcher registered, with the
 * token it registered; at the configured fallback when it registered no URL; nowhere when there is
 * neither. The query string is built encoded, so a value holding {@code &}, {@code =} or a space
 * cannot inject or truncate a parameter.
 */
class LogClientTest {

  private static final String FALLBACK = "http://service-dispatcher/api/v1/logs/stream";

  private final DispatcherRepository dispatchers = mock(DispatcherRepository.class);
  private final LogClient logClient = new LogClient(null, dispatchers, FALLBACK);

  private static TaskRunEntity taskClaimedBy(String dispatcherId) {
    TaskRunEntity task = new TaskRunEntity();
    task.setId("taskrun-1");
    task.setWorkflowRef("workflow-1");
    task.setWorkflowRunRef("run-1");
    if (dispatcherId != null) {
      RunClaim claim = new RunClaim();
      claim.setBy(dispatcherId);
      task.setClaim(claim);
    }
    return task;
  }

  private void registered(String id, String logsUrl, String logsToken) {
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId(id);
    dispatcher.setLogsUrl(logsUrl);
    dispatcher.setLogsToken(logsToken);
    when(dispatchers.findById(id)).thenReturn(Optional.of(dispatcher));
  }

  @Test
  void aTaskIsReadAtTheUrlItsDispatcherRegisteredWithItsToken() {
    registered("d-1", "http://kube-dispatcher:7702/api/v1/logs/stream", "secret-1");

    LogClient.LogSource source = logClient.resolve(taskClaimedBy("d-1")).orElseThrow();

    assertThat(source.uri().toString())
        .isEqualTo(
            "http://kube-dispatcher:7702/api/v1/logs/stream"
                + "?workflowRef=workflow-1&workflowRunRef=run-1&taskRunRef=taskrun-1");
    assertThat(source.token()).isEqualTo("secret-1");
  }

  @Test
  void aDispatcherThatRegisteredNoUrlIsReadAtTheFallbackWithItsToken() {
    registered("d-2", null, "secret-2");

    LogClient.LogSource source = logClient.resolve(taskClaimedBy("d-2")).orElseThrow();

    assertThat(source.uri().toString()).startsWith(FALLBACK + "?");
    assertThat(source.token()).isEqualTo("secret-2");
  }

  @Test
  void anUnclaimedTaskIsReadAtTheFallbackWithNoToken() {
    LogClient.LogSource source = logClient.resolve(taskClaimedBy(null)).orElseThrow();

    assertThat(source.uri().toString()).startsWith(FALLBACK + "?");
    assertThat(source.token()).isNull();
  }

  @Test
  void noRegisteredUrlAndNoFallbackIsNoSource() {
    registered("d-3", "", null);
    LogClient withoutFallback = new LogClient(null, dispatchers, "");

    assertThat(withoutFallback.resolve(taskClaimedBy("d-3"))).isEmpty();
    assertThat(withoutFallback.resolve(taskClaimedBy(null))).isEmpty();
  }

  @Test
  void encodesAmpersandEqualsAndSpaceInParamValues() {
    URI uri = LogClient.buildLogStreamUri(FALLBACK, "wf id", "run&ref", "task=run");

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
}
