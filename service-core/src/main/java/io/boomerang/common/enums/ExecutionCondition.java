package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * When a dependency lets its task run: once the task it names succeeded, failed, or ended either
 * way; or, for a dependency on {@code end} alone, once the whole run has ended, any way.
 */
public enum ExecutionCondition {
  always,
  success,
  failure, // NOSONAR
  /** Reads and writes as {@code finally}, which Java reserves as a name. */
  @JsonProperty("finally")
  Finally
}
