package io.boomerang.flow.sdk;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * Boomerang Flow as a Java object: the public API ({@code /api/v2}, {@code contracts/api-v2.yaml})
 * behind typed calls. One instance per server and token; thread-safe, no threads of its own.
 *
 * <pre>{@code
 * Flow flow = Flow.connect("https://flow.example.com", token);
 * WorkflowRun run = flow.runs("my-workspace").submit("analysis", SubmitRequest.of(params, labels));
 * }</pre>
 *
 * <p>Every call either returns the server's answer or throws {@link FlowApiException} carrying the
 * server's error; a server that cannot be reached throws Spring's {@link
 * org.springframework.web.client.ResourceAccessException}. Retries and timeouts are the host's
 * {@link RestClient}'s, not the library's.
 */
public final class Flow {

  private final ApiClient api;

  private Flow(ApiClient api) {
    this.api = api;
  }

  /** Connect to the server at {@code baseUrl} with a plain JDK client: connect 10 s, read 60 s. */
  public static Flow connect(String baseUrl, String token) {
    return builder().baseUrl(baseUrl).token(token).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The workflow runs of one workspace: submit, start, read, pause, cancel, retry, query, count. */
  public Runs runs(String workspace) {
    return new Runs(api, workspace);
  }

  /** The task runs of one workspace, queried across workflow runs. */
  public TaskRuns taskRuns(String workspace) {
    return new TaskRuns(api, workspace);
  }

  /** The Insights of one workspace: run and task-run statistics over a period. */
  public Insights insights(String workspace) {
    return new Insights(api, workspace);
  }

  /**
   * The Insights across workspaces, for a global grant: the same summary and task breakdown over
   * the workspaces an {@link InsightsQuery} names, or every one, and a page of task runs with
   * their workspace.
   */
  public Insights insights() {
    return new Insights(api, null);
  }

  /** Workspaces: create, ensure by external reference, read, query, delete. */
  public Workspaces workspaces() {
    return new Workspaces(api);
  }

  /** The manual and approval actions of one workspace: read, query and decide. */
  public Actions actions(String workspace) {
    return new Actions(api, workspace);
  }

  /**
   * The server's health probe ({@code GET /health}): {@code UP} when it serves, {@code DOWN} when
   * it answers that it does not. A server that cannot be reached throws {@link
   * org.springframework.web.client.ResourceAccessException}, as every call does.
   */
  public String health() {
    return api.health();
  }

  public static final class Builder {

    private String baseUrl;
    private String token;
    private RestClient restClient;

    private Builder() {}

    /** The server's origin, such as {@code https://flow.example.com}; the path is added. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** A Flow token - a workspace token or a global one - sent as {@code Authorization: Bearer}. */
    public Builder token(String token) {
      this.token = token;
      return this;
    }

    /**
     * Call the server through this client, whose transport - proxy, trust store, timeouts - is
     * kept; its status handling is replaced by the library's. Absent, a plain JDK client is made.
     */
    public Builder restClient(RestClient restClient) {
      this.restClient = restClient;
      return this;
    }

    public Flow build() {
      Assert.hasText(baseUrl, "baseUrl must not be empty");
      Assert.hasText(token, "token must not be empty");
      RestClient http = (restClient != null) ? restClient : ApiClient.defaultHttp();
      return new Flow(new ApiClient(http, baseUrl, token));
    }
  }
}
