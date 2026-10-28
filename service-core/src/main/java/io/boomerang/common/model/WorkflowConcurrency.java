package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.ConcurrencyOverflow;
import lombok.Data;

/**
 * A workflow's concurrency setting: at most {@code limit} of its runs in flight at once, and what
 * happens to a run over it - {@code queue} (the default) parks it until a run ends, {@code skip}
 * ends it at once. Absent means no limit beyond the workspace quota.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowConcurrency {

  private Integer limit;
  private ConcurrencyOverflow overflow;

  /** Whether the setting limits anything: a positive {@code limit}. */
  public boolean limits() {
    return limit != null && limit > 0;
  }

  public boolean skips() {
    return ConcurrencyOverflow.skip.equals(overflow);
  }
}
