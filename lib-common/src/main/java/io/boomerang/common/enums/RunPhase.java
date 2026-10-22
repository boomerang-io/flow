package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum RunPhase {
  queued("queued"),
  pending("pending"),
  running("running"),
  completed("completed"),
  finalized("finalized"); // NOSONAR

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
