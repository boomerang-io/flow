package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The node's retry budget as the engine holds it when a task is handed out: {@code max} is the
 * node's {@code retry.max}, absent when the node asked for no retry, and {@code failures} how many
 * reported failures the engine has already retried. The engine's own requeues of an attempt that
 * never ran are not counted here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRunRetry {

  private Integer max;
  private Integer failures;

  public Integer getMax() {
    return max;
  }

  public void setMax(Integer max) {
    this.max = max;
  }

  public Integer getFailures() {
    return failures;
  }

  public void setFailures(Integer failures) {
    this.failures = failures;
  }

  /**
   * Whether a failure reported now would be the node's last: the engine retries a reported failure
   * while {@code failures < max}, so a node gets {@code max + 1} attempts and this attempt is the
   * last once {@code failures >= max}. True when the node asked for no retry, so a handler that
   * keys its last-failure action off this needs no budget of its own.
   */
  @JsonIgnore
  public boolean isLastAttempt() {
    return max == null || (failures != null ? failures : 0) >= max;
  }
}
