package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.WorkflowRun;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** How a Flow is built: the base URL, the bearer, and the host's client when it hands one in. */
class FlowTest {

  @Test
  void connectTalksToTheServerUnderTheApiPathWithTheBearer() throws Exception {
    List<String> seen = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI()
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "{\"id\":\"r1\",\"status\":\"running\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      Flow flow = Flow.connect("http://127.0.0.1:" + server.getAddress().getPort(), "tok-1");

      WorkflowRun run = flow.runs("ws").get("r1", false);

      assertThat(run.status()).isEqualTo(RunStatus.running);
      assertThat(seen)
          .containsExactly("GET /api/v2/workspace/ws/workflowrun/r1?withTasks=false Bearer tok-1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void healthReadsTheProbeOutsideTheApiPathWhateverItsStatus() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://flow.example.com/health"))
        .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://flow.example.com/health"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"DOWN\"}"));
    server
        .expect(requestTo("https://flow.example.com/health"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    Flow flow =
        Flow.builder()
            .baseUrl("https://flow.example.com/")
            .token("tok-3")
            .restClient(builder.build())
            .build();

    assertThat(flow.health()).isEqualTo("UP");
    assertThat(flow.health()).isEqualTo("DOWN");
    assertThat(flow.health()).isEqualTo("DOWN");
    server.verify();
  }

  @Test
  void aHostsClientIsKeptWithItsTransport() {
    RestClient.Builder builder = RestClient.builder().defaultHeader("X-Host", "host-app");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(
            requestTo(
                "https://flow.example.com/api/v2/workspace/ws/workflowrun/r1?withTasks=true"))
        .andExpect(header("X-Host", "host-app"))
        .andExpect(header("Authorization", "Bearer tok-2"))
        .andRespond(withSuccess("{\"id\":\"r1\"}", MediaType.APPLICATION_JSON));

    Flow flow =
        Flow.builder()
            .baseUrl("https://flow.example.com")
            .token("tok-2")
            .restClient(builder.build())
            .build();

    assertThat(flow.runs("ws").get("r1").id()).isEqualTo("r1");
    server.verify();
  }

  @Test
  void aFlowNeedsABaseUrlAndAToken() {
    assertThatThrownBy(() -> Flow.builder().token("t").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
    assertThatThrownBy(() -> Flow.builder().baseUrl("http://flow").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token");
  }
}
