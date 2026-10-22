package io.boomerang.flow.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JavaType;

/**
 * The HTTP layer under every resource: the base path, the bearer, the SDK's JSON, and the
 * server's error shape turned into {@link FlowApiException}.
 */
final class ApiClient {

  /** The path every public route sits under. */
  static final String BASE_PATH = "/api/v2";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  private final RestClient http;

  ApiClient(RestClient http, String baseUrl, String token) {
    Assert.notNull(http, "http must not be null");
    Assert.hasText(baseUrl, "baseUrl must not be empty");
    this.http =
        http.mutate()
            .baseUrl(trimTrailingSlash(baseUrl) + BASE_PATH)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultStatusHandler(
                HttpStatusCode::isError,
                (request, response) -> {
                  throw FlowApiException.from(response);
                })
            .build();
  }

  /** A plain client with the timeouts a caller needs: connect 10 s, read 60 s. */
  static RestClient defaultHttp() {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  /** A read of {@code type} from a route; null when the server sent no body. */
  <T> T get(String path, Map<String, Object> pathVariables, Params query, JavaType type) {
    return read(http.get().uri(builder -> uri(builder, path, pathVariables, query)), type);
  }

  <T> T post(
      String path, Map<String, Object> pathVariables, Params query, Object body, JavaType type) {
    return read(
        http.post()
            .uri(builder -> uri(builder, path, pathVariables, query))
            .contentType(MediaType.APPLICATION_JSON)
            .body(WireFormat.JSON.writeValueAsBytes(body)),
        type);
  }

  <T> T put(String path, Map<String, Object> pathVariables, Object body, JavaType type) {
    return read(
        http.put()
            .uri(builder -> uri(builder, path, pathVariables, Params.none()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(WireFormat.JSON.writeValueAsBytes(body)),
        type);
  }

  <T> T delete(String path, Map<String, Object> pathVariables, JavaType type) {
    return read(
        http.method(HttpMethod.DELETE)
            .uri(builder -> uri(builder, path, pathVariables, Params.none())),
        type);
  }

  private static <T> T read(RestClient.RequestHeadersSpec<?> request, JavaType type) {
    byte[] body = request.accept(MediaType.APPLICATION_JSON).retrieve().body(byte[].class);
    return (body == null || body.length == 0) ? null : WireFormat.JSON.readValue(body, type);
  }

  // Path and query values travel as URI variables, so they are fully encoded - a label value may
  // hold any character, '=' and ',' included.
  private static URI uri(
      UriBuilder builder, String path, Map<String, Object> pathVariables, Params query) {
    builder.path(path);
    Map<String, Object> variables = new HashMap<>(pathVariables);
    query.apply(builder, variables);
    return builder.build(variables);
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
