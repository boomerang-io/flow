package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Dispatch envelope handed to a dispatcher/worker on claim. Wraps the public {@link WorkflowRun}
 * with the fencing tokens (claim seq + lease deadline) so the worker can echo the seq on its
 * lifecycle callbacks without leaking the tokens onto the public run model.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRunDispatch {

  private WorkflowRun run;
  private Long claimSeq;
  private Date leaseExpiresAt;

  public WorkflowRunDispatch() {
    // Default constructor
  }

  public WorkflowRunDispatch(WorkflowRun run, Long claimSeq, Date leaseExpiresAt) {
    this.run = run;
    this.claimSeq = claimSeq;
    this.leaseExpiresAt = leaseExpiresAt;
  }
}
