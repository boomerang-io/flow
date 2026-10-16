package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Retry state for a run attempt. Absent on a run means fully eligible. {@code after} gates claim
 * eligibility until the backoff elapses; {@code count} is the attempts consumed so far.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunRetry {

  private Date after;
  private int count;
}
