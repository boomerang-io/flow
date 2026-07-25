package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Ownership block written by a Compare-And-Set claim. {@code by} absent on a run means unclaimed
 * and eligible. A requeue clears {@code by}/{@code at}/{@code leaseExpiresAt} only; {@code seq}
 * increments on every claim and is never cleared, so it fences out superseded claimants.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunClaim {

  private String by;
  private Date at;
  private Date leaseExpiresAt;
  private Long seq;
}
