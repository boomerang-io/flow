package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Ownership block written by a Compare-And-Set claim. Absent on a run means unclaimed and
 * eligible; the paired top-level {@code claimEpoch} fencing token lives outside this block so it
 * survives when a requeue clears the claim.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunClaim {

  private String by;
  private Date at;
  private Date leaseExpiresAt;
}
