package io.boomerang.common.model;

import java.util.LinkedList;
import java.util.List;
import lombok.Data;

@Data
public class WorkflowRunInsight {

  private Long totalRuns = 0L;
  private Long concurrentRuns = 0L;
  private Long totalDuration = 0L;
  private Long medianDuration = 0L;
  private List<WorkflowRunSummary> runs = new LinkedList<>();

  @Override
  public String toString() {
    return "WorkflowRunInsight [totalRuns="
        + totalRuns
        + ", concurrentRuns="
        + concurrentRuns
        + ", totalDuration="
        + totalDuration
        + ", medianDuration="
        + medianDuration
        + ", runs="
        + runs
        + "]";
  }
}
