package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Dispatch envelope handed to a dispatcher/worker on claim. Wraps the public {@link TaskRun} with
 * the fencing tokens (claim seq + lease deadline) so the worker can echo the seq on its lifecycle
 * callbacks without leaking the tokens onto the public run model.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRunDispatch {

  private TaskRun run;
  private Long claimSeq;
  private Date leaseExpiresAt;

  public TaskRunDispatch() {
    // Default constructor
  }

  public TaskRunDispatch(TaskRun run, Long claimSeq, Date leaseExpiresAt) {
    this.run = run;
    this.claimSeq = claimSeq;
    this.leaseExpiresAt = leaseExpiresAt;
  }
}
