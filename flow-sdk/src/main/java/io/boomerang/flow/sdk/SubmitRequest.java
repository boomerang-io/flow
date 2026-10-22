package io.boomerang.flow.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.boomerang.flow.sdk.model.RunParam;
import io.boomerang.flow.sdk.model.Trigger;
import io.boomerang.flow.sdk.model.WorkflowWorkspace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body of {@link Runs#submit}: the run's params, labels and annotations, and the settings
 * that override the workflow's own. On the wire this is the server's {@code
 * WorkflowSubmitRequest}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SubmitRequest {

  @JsonProperty private final List<RunParam> params = new ArrayList<>();
  @JsonProperty private final Map<String, String> labels = new LinkedHashMap<>();
  @JsonProperty private final Map<String, Object> annotations = new LinkedHashMap<>();
  @JsonProperty private WorkflowWorkspace workspace;
  @JsonProperty private Long timeout;
  @JsonProperty private Long retries;
  @JsonProperty private Boolean debug;
  @JsonProperty private Integer workflowVersion;
  @JsonProperty private Trigger trigger;

  private SubmitRequest() {}

  /** A run with the workflow's own params and no labels. */
  public static SubmitRequest empty() {
    return new SubmitRequest();
  }

  public static SubmitRequest of(List<RunParam> params, Map<String, String> labels) {
    return empty().params(params).labels(labels);
  }

  public SubmitRequest param(String name, Object value) {
    params.add(RunParam.of(name, value));
    return this;
  }

  public SubmitRequest params(List<RunParam> params) {
    this.params.addAll(params);
    return this;
  }

  /** A label on the run, by which it and its task runs can be queried. */
  public SubmitRequest label(String key, String value) {
    labels.put(key, value);
    return this;
  }

  public SubmitRequest labels(Map<String, String> labels) {
    this.labels.putAll(labels);
    return this;
  }

  public SubmitRequest annotation(String key, Object value) {
    annotations.put(key, value);
    return this;
  }

  /** The run's shared workspace, overriding the workflow's declaration. */
  public SubmitRequest workspace(WorkflowWorkspace workspace) {
    this.workspace = workspace;
    return this;
  }

  /** The run's timeout in minutes, overriding the workflow's. */
  public SubmitRequest timeout(long minutes) {
    this.timeout = minutes;
    return this;
  }

  public SubmitRequest retries(long retries) {
    this.retries = retries;
    return this;
  }

  public SubmitRequest debug(boolean debug) {
    this.debug = debug;
    return this;
  }

  /** The workflow version to run; absent, the latest. */
  public SubmitRequest workflowVersion(int version) {
    this.workflowVersion = version;
    return this;
  }

  /** What the run records as its trigger; absent, the server's default for an API submit. */
  public SubmitRequest trigger(Trigger trigger) {
    this.trigger = trigger;
    return this;
  }

  public List<RunParam> params() {
    return params;
  }

  public Map<String, String> labels() {
    return labels;
  }

  public Map<String, Object> annotations() {
    return annotations;
  }

  // Params are left out: a param may carry a secret.
  @Override
  public String toString() {
    return "SubmitRequest[labels=" + labels + ", workflowVersion=" + workflowVersion + "]";
  }
}
