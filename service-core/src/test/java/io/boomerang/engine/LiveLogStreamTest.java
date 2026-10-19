package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.RunClaim;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * A running task's log reaches the reader as the dispatcher writes it: the relay passes each read
 * on and flushes it, rather than holding the body until the task ends. The dispatcher route here
 * writes its second line only after the reader has received the first, so a relay that buffered
 * would never see the second line.
 */
class LiveLogStreamTest {

  private HttpServer dispatcherRoute;
  private final CountDownLatch firstLineReachedReader = new CountDownLatch(1);
  private final AtomicBoolean secondLineWaitedForReader = new AtomicBoolean();

  @BeforeEach
  void startDispatcherRoute() throws IOException {
    dispatcherRoute = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    dispatcherRoute.createContext(
        "/api/v1/logs/stream",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/plain");
          exchange.sendResponseHeaders(200, 0); // chunked
          try (OutputStream body = exchange.getResponseBody()) {
            body.write("tick-1\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
              secondLineWaitedForReader.set(firstLineReachedReader.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            body.write("tick-2\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
          }
        });
    dispatcherRoute.start();
  }

  @AfterEach
  void stopDispatcherRoute() {
    dispatcherRoute.stop(0);
  }

  @Test
  void theFirstLineReachesTheReaderBeforeTheDispatcherWritesTheSecond() throws Exception {
    DispatcherRepository dispatchers = mock(DispatcherRepository.class);
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId("d-1");
    dispatcher.setLogsUrl(
        "http://127.0.0.1:" + dispatcherRoute.getAddress().getPort() + "/api/v1/logs/stream");
    when(dispatchers.findById("d-1")).thenReturn(Optional.of(dispatcher));
    // The same client type as the streaming template the product wires (HttpComponents).
    LogClient logClient =
        new LogClient(new RestTemplate(new HttpComponentsClientHttpRequestFactory()), dispatchers);

    TaskRunEntity task = new TaskRunEntity();
    task.setId("taskrun-1");
    task.setWorkflowRef("workflow-1");
    task.setWorkflowRunRef("run-1");
    RunClaim claim = new RunClaim();
    claim.setBy("d-1");
    task.setClaim(claim);

    ByteArrayOutputStream received = new ByteArrayOutputStream();
    OutputStream reader =
        new OutputStream() {
          @Override
          public void write(int b) {
            received.write(b);
          }

          @Override
          public void write(byte[] bytes, int off, int len) {
            received.write(bytes, off, len);
          }

          // What a servlet response does on flush: hand the bytes so far to the client.
          @Override
          public void flush() {
            if (received.toString(StandardCharsets.UTF_8).contains("tick-1")) {
              firstLineReachedReader.countDown();
            }
          }
        };

    logClient.streamLog(task).writeTo(reader);

    assertThat(secondLineWaitedForReader).isTrue();
    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("tick-1\ntick-2\n");
  }

  @Test
  void eachReadIsWrittenAndFlushedAsItArrives() throws IOException {
    int[] flushes = {0};
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream counting =
        new OutputStream() {
          @Override
          public void write(int b) {
            sink.write(b);
          }

          @Override
          public void flush() {
            flushes[0]++;
          }
        };
    InputStream twoReads =
        new SequenceInputStream(
            new ByteArrayInputStream("a\n".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayInputStream("b\n".getBytes(StandardCharsets.UTF_8)));

    long copied = LogClient.copyAsWritten(twoReads, counting);

    assertThat(copied).isEqualTo(4);
    assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("a\nb\n");
    assertThat(flushes[0]).isEqualTo(2);
  }
}
