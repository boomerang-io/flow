package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/** A Flow server in memory: a {@link Flow} bound to a {@link MockRestServiceServer}. */
final class StubServer {

  static final String ORIGIN = "http://flow:7700";
  static final String API = ORIGIN + "/api/v2";
  static final String TOKEN = "secret";

  final MockRestServiceServer server;
  final Flow flow;

  StubServer() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    flow = Flow.builder().baseUrl(ORIGIN + "/").token(TOKEN).restClient(builder.build()).build();
  }

  // The body as JSON, compared as a tree so field order does not matter and absent fields do.
  static RequestMatcher jsonBody(String expected) {
    return request ->
        assertThat(WireFormat.JSON.readTree(((MockClientHttpRequest) request).getBodyAsBytes()))
            .isEqualTo(WireFormat.JSON.readTree(expected));
  }

  /** A page of the given rows, as Spring Data serialises one. */
  static String page(int number, int size, long total, boolean last, String... rows) {
    return "{\"content\":["
        + String.join(",", rows)
        + "],\"pageable\":{\"pageNumber\":"
        + number
        + "},\"totalElements\":"
        + total
        + ",\"totalPages\":2,\"last\":"
        + last
        + ",\"size\":"
        + size
        + ",\"number\":"
        + number
        + ",\"sort\":{\"sorted\":true},\"numberOfElements\":"
        + rows.length
        + ",\"first\":"
        + (number == 0)
        + ",\"empty\":"
        + (rows.length == 0)
        + "}";
  }
}
