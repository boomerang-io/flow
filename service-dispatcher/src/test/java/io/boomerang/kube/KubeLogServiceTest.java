package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.client.EngineClient;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

  @Test
  public void testGetPodLogWithCallbackInvokesItOnNormalCompletion() throws Exception {
    KubeLogService service = new KubeLogService(null, null);
    byte[] payload = "hello".getBytes();
    AtomicInteger callbackCount = new AtomicInteger(0);

    StreamingResponseBody body =
        service.getPodLog(new ByteArrayInputStream(payload), "pod-3", callbackCount::incrementAndGet);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.writeTo(output);

    assertArrayEquals(payload, output.toByteArray());
    assertEquals(1, callbackCount.get());
  }

  // Reproduces what happens when the termination poller closes the LogWatch mid-stream: the
  // InputStream throws IOException on its next read. With a callback given, that must be treated
  // as a normal end of stream (not propagated) and the callback must still run - this is exactly
  // what lets a running Pod's log stream end once the Pod goes terminal, instead of hanging.
  @Test
  public void testGetPodLogWithCallbackSwallowsSelfInitiatedCloseAndRunsCallback() throws Exception {
    KubeLogService service = new KubeLogService(null, null);
    AtomicInteger callbackCount = new AtomicInteger(0);
    InputStream closesMidStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("Stream closed");
          }
        };

    StreamingResponseBody body =
        service.getPodLog(closesMidStream, "pod-4", callbackCount::incrementAndGet);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.writeTo(output); // must not throw

    assertEquals(1, callbackCount.get());
  }

  // The two-arg overload (no callback) is what the pre-existing tests above exercise; pin that it
  // still propagates a genuine IOException rather than silently swallowing it - only the
  // callback-bearing overload used by the running-Pod watch path treats a closed stream as normal.
  @Test
  public void testGetPodLogWithoutCallbackStillPropagatesIOException() {
    KubeLogService service = new KubeLogService(null, null);
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("boom");
          }
        };

    assertThrows(IOException.class, () -> service.getPodLog(broken, "pod-5").writeTo(new ByteArrayOutputStream()));
  }
}

/**
 * Exercises {@link KubeLogService#streamPodLog} end-to-end against the fabric8 mock server - no
 * real Kubernetes cluster needed. Pins the three branches that fixed a stream that never ended
 * for a finished Pod: no Pod found yet, a Pod that's already terminal (must use the non-watching
 * {@code getLog()}, not {@code watchLog()}), and a running Pod (must complete rather than hang).
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
class KubeLogServiceStreamTest {

  KubernetesClient client;

  KubernetesMockServer server;

  @Autowired private KubeLogService kubeLogService;

  @Autowired private KubeHelperService helperKubeService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeLogService.setClient(client);
  }

  private Map<String, String> taskLabels(String taskRunRef) {
    return helperKubeService.getTaskLabels("wf-1", "wfr-1", taskRunRef, null);
  }

  private Pod createPod(String taskRunRef, String phase) {
    PodStatus status = new PodStatus();
    status.setPhase(phase);
    Pod pod =
        new PodBuilder()
            .withNewMetadata()
            .withGenerateName("pod-" + taskRunRef + "-")
            .withLabels(new HashMap<>(taskLabels(taskRunRef)))
            .endMetadata()
            .withStatus(status)
            .build();
    Pod created = client.pods().resource(pod).create();
    created.setStatus(status);
    return client.pods().resource(created).updateStatus();
  }

  @Test
  public void testStreamPodLogWithNoPodEndsImmediatelyWithAMessage() throws Exception {
    StreamingResponseBody body =
        kubeLogService.streamPodLog(null, "wf-1", "wfr-1", "taskrun-no-pod", null);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.writeTo(output); // must return promptly, not hang

    assertTrue(output.toString().contains("No Pod"));
  }

  @Test
  public void testStreamPodLogForTerminatedPodReadsTheLogOnceInsteadOfWatching() throws Exception {
    String taskRunRef = "taskrun-terminated";
    Pod pod = createPod(taskRunRef, "Succeeded");
    String podName = pod.getMetadata().getName();
    String namespace = pod.getMetadata().getNamespace();

    // Stub only the non-watching getLog() path (`pretty=true`, no `follow=true`). If the fix
    // regressed and streamPodLog opened a watchLog() on an already-terminal Pod instead, this
    // expectation would never be hit and the mock's crud fallback (not this exact text) would
    // answer instead.
    server
        .expect()
        .get()
        .withPath("/api/v1/namespaces/" + namespace + "/pods/" + podName + "/log?pretty=true")
        .andReturn(200, "hello from a finished pod")
        .once();

    StreamingResponseBody body =
        kubeLogService.streamPodLog(null, "wf-1", "wfr-1", taskRunRef, null);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.writeTo(output);

    assertEquals("hello from a finished pod", output.toString());
  }

  @Test
  public void testStreamPodLogForRunningPodCompletesWithoutHanging() throws Exception {
    String taskRunRef = "taskrun-running";
    createPod(taskRunRef, "Running");

    StreamingResponseBody body =
        kubeLogService.streamPodLog(null, "wf-1", "wfr-1", taskRunRef, null);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    // The mock server's watchLog response ends on its own (unlike a real cluster, which would
    // hang) - this pins that the running-Pod branch (watchLog + termination poller) wires up and
    // completes cleanly rather than throwing.
    body.writeTo(output);
  }
}
