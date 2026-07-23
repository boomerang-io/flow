package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RetryClass;
import java.util.Date;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Retry state for a run attempt. Absent on a run means fully eligible. {@code after} gates claim
 * eligibility until the backoff elapses; {@code count} is the attempts consumed so far; the class
 * selects the backoff policy and attempt cap.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunRetry {

  private Date after;
  private int count;

  // Stored as "class" - reserved in Java, hence the field name.
  @Field("class")
  private RetryClass clazz;
}
