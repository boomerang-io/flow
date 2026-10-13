package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.DispatcherRegistrationRequest;
import io.boomerang.dispatcher.sdk.model.HeartbeatRequest;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskRunStartRequest;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseQuery;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.type.TypeReference;

/**
 * The engine's dispatcher routes, one method per route ({@code contracts/dispatcher-v1.yaml}).
 * Stateless: the dispatcher id is passed in, and no method starts a thread. A poll blocks the
 * calling thread for the engine's long poll, up to 30 s.
 *
 * <p>Errors are Spring's: a 4xx answer is {@link
 * org.springframework.web.client.HttpClientErrorException}, a 5xx answer {@link
 * org.springframework.web.client.HttpServerErrorException}, and an engine that cannot be reached
 * {@link org.springframework.web.client.ResourceAccessException}.
 */
public class DispatcherClient {

  /** The path every dispatcher route sits under. */
  public static final String BASE_PATH = "/api/v1/dispatcher";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  // Longer than the engine's 30 s long-poll hold, so a held poll is answered rather than cut.
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  private static final TypeReference<List<TaskRun>> TASK_RUNS = new TypeReference<>() {};
  private static final TypeReference<List<WorkflowRun>> WORKFLOW_RUNS = new TypeReference<>() {};

  private final RestClient http;

  /**
   * Call the engine at {@code engineUrl} through {@code http}, whose transport - proxy, TLS,
   * timeouts - is kept. Its read timeout MUST exceed 30 s. {@code token} is sent as {@code
   * Authorization: Bearer} on every call, and to the engine only; blank sends none.
   */
  public DispatcherClient(RestClient http, String engineUrl, String token) {
    Assert.notNull(http, "http must not be null");
    Assert.hasText(engineUrl, "engineUrl must not be empty");
    RestClient.Builder builder = http.mutate().baseUrl(trimTrailingSlash(engineUrl) + BASE_PATH);
    if (token != null && !token.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    this.http = builder.build();
  }

  /** Call the engine at {@code engineUrl} with a JDK client: connect 10 s, read 60 s. */
  public DispatcherClient(String engineUrl, String token) {
    this(defaultHttp(), engineUrl, token);
  }

  /** Return a plain client with the timeouts a dispatcher needs: connect 10 s, read 60 s. */
  public static RestClient defaultHttp() {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  /** {@code POST /register}: register, and return the dispatcher id for every later call. */
  public String register(DispatcherRegistrationRequest request) {
    String id =
        http.post()
            .uri("/register")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(request))
            .retrieve()
            .body(String.class);
    // The engine answers the bare id; tolerate it arriving as a JSON string.
    return (id == null) ? null : id.strip().replaceAll("^\"|\"$", "");
  }

  /**
   * {@code GET /{id}/tasks}: long-poll the task queue. Return the task runs claimed for this
   * dispatcher - orders to run and orders to terminate - or an empty list when the window passed
   * with none. {@code limit} is the most new task runs to claim; null sends none.
   */
  public List<TaskRun> pollTasks(String dispatcherId, Integer limit, TaskFilter filter) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("id", dispatcherId);
    byte[] body =
        http.get()
            .uri(builder -> taskQueueUri(builder, limit, filter, variables))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(byte[].class);
    return (body == null || body.length == 0) ? List.of() : WireFormat.JSON.readValue(body, TASK_RUNS);
  }

  /** {@code GET /{id}/workflows}: long-poll the workflow queue for runs to provision. */
  public List<WorkflowRun> pollWorkflows(String dispatcherId) {
    byte[] body =
        http.get()
            .uri("/{id}/workflows", dispatcherId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(byte[].class);
    return (body == null || body.length == 0)
        ? List.of()
        : WireFormat.JSON.readValue(body, WORKFLOW_RUNS);
  }

  /**
   * {@code PUT /taskrun/{id}/start}: say a task is starting. Return the engine's answer, or null
   * when it sent none; an answer in phase {@code completed} means do not run it.
   */
  public TaskRun startTask(String taskRunId, TaskRunStartRequest request) {
    byte[] body =
        http.put()
            .uri("/taskrun/{id}/start", taskRunId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(request))
            .retrieve()
            .body(byte[].class);
    return (body == null || body.length == 0) ? null : WireFormat.JSON.readValue(body, TaskRun.class);
  }

  /** {@code PUT /taskrun/{id}/end}: report how a task ended. */
  public void endTask(String taskRunId, TaskRunEndRequest request) {
    http.put()
        .uri("/taskrun/{id}/end", taskRunId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json(request))
        .retrieve()
        .toBodilessEntity();
  }

  /** {@code PUT /{id}/heartbeat}: renew the lease of each task run still being worked on. */
  public void heartbeat(String dispatcherId, List<String> taskRunIds) {
    http.put()
        .uri("/{id}/heartbeat", dispatcherId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json(new HeartbeatRequest(taskRunIds)))
        .retrieve()
        .toBodilessEntity();
  }

  /** {@code PUT /workflowrun/{id}/start}: report a claimed workflow run's storage ready. */
  public void startWorkflowRun(String workflowRunId) {
    http.put()
        .uri("/workflowrun/{id}/start", workflowRunId)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{}".getBytes(StandardCharsets.UTF_8))
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * {@code POST /workspaces/releasable}: ask which of the held volumes' owners are finished.
   * Return an empty answer when the engine sent none.
   */
  public WorkspaceReleaseResponse releasable(WorkspaceReleaseQuery query) {
    byte[] body =
        http.post()
            .uri("/workspaces/releasable")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(query))
            .retrieve()
            .body(byte[].class);
    return (body == null || body.length == 0)
        ? new WorkspaceReleaseResponse()
        : WireFormat.JSON.readValue(body, WorkspaceReleaseResponse.class);
  }

  // Filter values travel as URI variables, so they are fully encoded - a label value may hold any
  // character, '=' and '+' included.
  private static URI taskQueueUri(
      UriBuilder builder, Integer limit, TaskFilter filter, Map<String, Object> variables) {
    builder.path("/{id}/tasks");
    if (limit != null) {
      builder.queryParam("limit", limit);
    }
    TaskFilter applied = (filter != null) ? filter : TaskFilter.none();
    if (!applied.getTypes().isEmpty()) {
      builder.queryParam("type", "{type}");
      variables.put("type", String.join(",", applied.getTypes()));
    }
    if (!applied.getTasks().isEmpty()) {
      builder.queryParam("task", "{task}");
      variables.put("task", String.join(",", applied.getTasks()));
    }
    if (applied.getWorkflowLabel() != null) {
      builder.queryParam("workflowLabel", "{workflowLabel}");
      variables.put("workflowLabel", applied.getWorkflowLabel());
    }
    return builder.build(variables);
  }

  private static byte[] json(Object value) {
    return WireFormat.JSON.writeValueAsBytes(value);
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
