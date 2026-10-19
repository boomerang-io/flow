package io.boomerang.common.model;

import java.util.LinkedList;
import java.util.List;
import lombok.Data;

/**
 * Model usage summed over the {@code ai} task runs of the runs in scope, from the results each task
 * reports. A result that is empty or not a number adds nothing; {@code cost} is the endpoint's own
 * figure, in its currency, and is null when no task run reported one. A for-each parent is not
 * counted, only its items.
 */
@Data
public class WorkflowRunInsightUsage {

  private long aiTaskRuns;
  private long promptTokens;
  private long completionTokens;
  private long totalTokens;
  private long reasoningTokens;
  private long cachedPromptTokens;
  private Double cost;
  /** The same sums per model the endpoint served, most tokens first. */
  private List<ModelUsage> byModel = new LinkedList<>();

  @Data
  public static class ModelUsage {
    /** The {@code model} result; null for task runs that reported none. */
    private String model;
    private long aiTaskRuns;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long reasoningTokens;
    private long cachedPromptTokens;
    private Double cost;
  }
}
