package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * The internal lifecycle position of a run beside its externally visible {@code RunStatus}.
 * {@code completed} is terminal: the run's storage release is the dispatcher's own reconciliation
 * against the cluster, never a further phase of the run.
 */
public enum RunPhase {
  queued("queued"),
  pending("pending"),
  running("running"),
  completed("completed"); // NOSONAR

  private String phase;

  RunPhase(String phase) {
    this.phase = phase;
  }

  @JsonValue
  public String getPhase() {
    return phase;
  }

  public static RunPhase getRunPhase(String phase) {
    return Arrays.asList(RunPhase.values()).stream()
        .filter(value -> value.getPhase().equals(phase))
        .findFirst()
        .orElse(null);
  }
}
