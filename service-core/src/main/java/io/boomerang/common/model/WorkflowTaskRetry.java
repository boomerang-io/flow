package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * A task node's retry setting: {@code max} attempts beyond the first for a failure its dispatcher
 * reports, each after {@code backoff}. Absent, or {@code max} 0, means one attempt. The engine's
 * own requeues of an attempt that never ran keep their separate budget.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowTaskRetry {

  private Integer max;
  private RetryBackoff backoff;
}
