package io.boomerang.flow.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.boomerang.flow.sdk.model.RetryScope;
import io.boomerang.flow.sdk.model.RunParam;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body of {@link Runs#retry}: what to run again, and the params and labels merged over the
 * origin run's own. On the wire this is the server's {@code WorkflowRunRetryRequest}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Retry {

  @JsonProperty private final RetryScope scope;
  @JsonProperty private final List<String> nodes = new ArrayList<>();
  @JsonProperty private final List<RunParam> params = new ArrayList<>();
  @JsonProperty private final Map<String, String> labels = new LinkedHashMap<>();

  private Retry(RetryScope scope) {
    this.scope = scope;
  }

  /** A fresh run of the whole graph. */
  public static Retry all() {
    return new Retry(RetryScope.all);
  }

  /** Every task that did not succeed, and everything after it; the rest is carried over. */
  public static Retry failed() {
    return new Retry(RetryScope.failed);
  }

  /** These tasks and everything after them; the rest is carried over. */
  public static Retry nodes(String... names) {
    Retry retry = new Retry(RetryScope.nodes);
    retry.nodes.addAll(Arrays.asList(names));
    return retry;
  }

  /** A param merged over the origin run's. */
  public Retry param(String name, Object value) {
    params.add(RunParam.of(name, value));
    return this;
  }

  public Retry params(List<RunParam> params) {
    this.params.addAll(params);
    return this;
  }

  /** A label merged over the origin run's. */
  public Retry label(String key, String value) {
    labels.put(key, value);
    return this;
  }

  public Retry labels(Map<String, String> labels) {
    this.labels.putAll(labels);
    return this;
  }

  public RetryScope scope() {
    return scope;
  }

  public List<String> nodes() {
    return nodes;
  }

  public List<RunParam> params() {
    return params;
  }

  public Map<String, String> labels() {
    return labels;
  }

  @Override
  public String toString() {
    return "Retry[scope=" + scope + ", nodes=" + nodes + ", labels=" + labels + "]";
  }
}
