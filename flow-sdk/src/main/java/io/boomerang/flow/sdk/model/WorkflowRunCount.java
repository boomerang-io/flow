package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** How many runs match a query, by status; a status with no run is absent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRunCount(Map<String, Long> status) {

  /** The count of one status, 0 when absent. */
  public long of(RunStatus status) {
    return (this.status == null) ? 0 : this.status.getOrDefault(status.name(), 0L);
  }

  /** The count over every status. */
  public long total() {
    return (status == null) ? 0 : status.values().stream().mapToLong(Long::longValue).sum();
  }
}
