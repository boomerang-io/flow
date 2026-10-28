package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Retry state for a task run. {@code after} gates claim eligibility until the backoff elapses.
 * Two budgets: {@code count} is the engine's requeues of an attempt that never ran (a lost lease, a
 * deadline, a could-not-start report), capped by the engine; {@code failures} is the retries of a
 * reported failure the node asked for, capped by {@code max}. {@code max} and {@code backoff} are
 * the node's setting, copied onto the run when its task list is built so the end path reads one
 * document; absent means the node asked for none.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunRetry {

  private Date after;
  private int count;
  private Integer max;
  private RetryBackoff backoff;
  private int failures;
}
