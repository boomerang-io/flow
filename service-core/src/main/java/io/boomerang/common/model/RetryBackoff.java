package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.BackoffType;
import lombok.Data;

/**
 * The wait before a task's next attempt, in seconds. {@code fixed} waits {@code initial} every
 * time; {@code exponential} doubles from {@code initial} up to {@code max}. A null field takes the
 * engine's default (10 s, doubling to 5 min).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetryBackoff {

  private BackoffType type;
  private Long initial;
  private Long max;
}
