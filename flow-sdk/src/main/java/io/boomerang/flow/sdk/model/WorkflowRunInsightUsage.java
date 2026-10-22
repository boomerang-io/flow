package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Model usage summed over the {@code ai} task runs in scope, from the results each reported.
 * {@code cost} is the endpoint's own figure, in its currency, null when none reported one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunInsightUsage(
    long aiTaskRuns,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long reasoningTokens,
    long cachedPromptTokens,
    Double cost,
    List<ModelUsage> byModel) {

  /** The same sums for one model the endpoint served; {@code model} null when none reported. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ModelUsage(
      String model,
      long aiTaskRuns,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long reasoningTokens,
      long cachedPromptTokens,
      Double cost) {}
}
