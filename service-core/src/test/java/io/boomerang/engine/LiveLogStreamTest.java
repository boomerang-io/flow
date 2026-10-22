package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.util.ResponseFlushingOutputStream;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * A running task's log reaches the reader as the dispatcher writes it. The dispatcher route here
 * writes its second line only after the reader has received the first, so a relay that buffered
 * would never see the second line in time.
 *
 * <p>The reader is what Spring really hands a {@link StreamingResponseBody}: a body stream whose
 * own flush does nothing ({@link StreamUtils#nonFlushing}), with bytes reaching the client only when
 * the servlet response is flushed. A line counts as received when {@link
 * HttpServletResponse#flushBuffer()} runs after it was written.
 */
class LiveLogStreamTest {

  private static final long SECOND_LINE_WAIT_SECONDS = 2;

  private HttpServer dispatcherRoute;
  private final CountDownLatch firstLineReachedReader = new CountDownLatch(1);
  private final AtomicBoolean secondLineWaitedForReader = new AtomicBoolean();
  private final CountDownLatch quietRouteReleased = new CountDownLatch(1);

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
              secondLineWaitedForReader.set(
                  firstLineReachedReader.await(SECOND_LINE_WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            body.write("tick-2\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
          }
        });
    // A task that writes one line and then nothing for longer than the relay's read allows.
    dispatcherRoute.createContext(
        "/quiet/stream",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/plain");
          exchange.sendResponseHeaders(200, 0); // chunked
          try (OutputStream body = exchange.getResponseBody()) {
            body.write("start\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
              quietRouteReleased.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          } catch (IOException e) {
            // The relay gave up on this route first; nothing left to write.
          }
        });
    // A route that refuses before sending anything, as a dispatcher that no longer has the pod does.
    dispatcherRoute.createContext(
        "/refusing/stream",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    dispatcherRoute.start();
  }

  @AfterEach
  void stopDispatcherRoute() {
    quietRouteReleased.countDown();
    dispatcherRoute.stop(0);
  }

  private LogClient logClient() {
    return logClient("/api/v1/logs/stream", new HttpComponentsClientHttpRequestFactory());
  }

  private LogClient logClient(String routePath, HttpComponentsClientHttpRequestFactory factory) {
    DispatcherRepository dispatchers = mock(DispatcherRepository.class);
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId("d-1");
    dispatcher.setLogsUrl("http://127.0.0.1:" + dispatcherRoute.getAddress().getPort() + routePath);
    when(dispatchers.findById("d-1")).thenReturn(Optional.of(dispatcher));
    // The same client type as the streaming template the product wires (HttpComponents).
    return new LogClient(new RestTemplate(factory), dispatchers);
  }

  private static TaskRunEntity runningTask() {
    TaskRunEntity task = new TaskRunEntity();
    task.setId("taskrun-1");
    task.setWorkflowRef("workflow-1");
    task.setWorkflowRunRef("run-1");
    RunClaim claim = new RunClaim();
    claim.setBy("d-1");
    task.setClaim(claim);
    return task;
  }

  // A servlet response whose flush is the moment the bytes written so far reach the client.
  private HttpServletResponse responseReceivingInto(ByteArrayOutputStream received)
      throws IOException {
    HttpServletResponse response = mock(HttpServletResponse.class);
    doAnswer(
            invocation -> {
              if (received.toString(StandardCharsets.UTF_8).contains("tick-1")) {
                firstLineReachedReader.countDown();
              }
              return null;
            })
        .when(response)
        .flushBuffer();
    return response;
  }

  @Test
  void theFirstLineReachesTheReaderBeforeTheDispatcherWritesTheSecond() throws Exception {
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    HttpServletResponse response = responseReceivingInto(received);

    ResponseFlushingOutputStream.flushing(logClient().streamLog(runningTask()), response)
        .writeTo(StreamUtils.nonFlushing(received));

    assertThat(secondLineWaitedForReader).isTrue();
    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("tick-1\ntick-2\n");
  }

  @Test
  void withoutFlushingTheResponseTheReaderSeesNothingUntilTheEnd() throws Exception {
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    responseReceivingInto(received);

    // The body's own flushes land on Spring's non-flushing stream and reach nobody.
    logClient().streamLog(runningTask()).writeTo(StreamUtils.nonFlushing(received));

    assertThat(secondLineWaitedForReader).isFalse();
    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("tick-1\ntick-2\n");
  }

  @Test
  void aTaskSilentPastTheReadLimitEndsTheStreamWithALineSayingSo() throws Exception {
    HttpComponentsClientHttpRequestFactory shortRead = new HttpComponentsClientHttpRequestFactory();
    shortRead.setReadTimeout(Duration.ofMillis(500));
    ByteArrayOutputStream received = new ByteArrayOutputStream();

    logClient("/quiet/stream", shortRead)
        .streamLog(runningTask())
        .writeTo(StreamUtils.nonFlushing(received));

    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("start\n" + LogClient.IDLE_MESSAGE);
  }

  @Test
  void aTaskWithNoLiveRouteIsAnsweredByTheFallback() throws Exception {
    DispatcherRepository dispatchers = mock(DispatcherRepository.class);
    when(dispatchers.findById("d-1")).thenReturn(Optional.empty());
    ByteArrayOutputStream received = new ByteArrayOutputStream();

    new LogClient(new RestTemplate(), dispatchers)
        .streamLog(runningTask(), out -> out.write("stored\n".getBytes(StandardCharsets.UTF_8)))
        .writeTo(received);

    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("stored\n");
  }

  @Test
  void aRouteThatFailsBeforeAnsweringIsAnsweredByTheFallback() throws Exception {
    ByteArrayOutputStream received = new ByteArrayOutputStream();

    logClient("/refusing/stream", new HttpComponentsClientHttpRequestFactory())
        .streamLog(runningTask(), out -> out.write("stored\n".getBytes(StandardCharsets.UTF_8)))
        .writeTo(received);

    assertThat(received.toString(StandardCharsets.UTF_8)).isEqualTo("stored\n");
  }

  @Test
  void aLiveStreamIsServedAheadOfTheFallback() throws Exception {
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    firstLineReachedReader.countDown();

    logClient()
        .streamLog(runningTask(), out -> out.write("stored\n".getBytes(StandardCharsets.UTF_8)))
        .writeTo(received);

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
