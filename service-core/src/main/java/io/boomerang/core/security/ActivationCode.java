package io.boomerang.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The activation code of an empty install: {@code flow.activation-code} (env {@code
 * FLOW_ACTIVATION_CODE}), at least 32 characters, else the start fails. The retired name {@code
 * flow.otc} is read when the new one is unset. Unset, activation is off: nobody can create the first
 * admin by code, and the start logs one warning.
 */
@Component
public class ActivationCode {

  private static final Logger LOGGER = LogManager.getLogger();

  static final int MIN_LENGTH = 32;

  private final String code;

  public ActivationCode(
      @Value("${flow.activation-code:}") String code,
      @Value("${flow.otc:}") String legacyCode,
      AdminClaim adminClaim) {
    boolean legacySet = legacyCode != null && !legacyCode.isBlank();
    boolean set = code != null && !code.isBlank();
    if (set && legacySet && !code.equals(legacyCode)) {
      throw new IllegalStateException(
          "flow.activation-code and the retired flow.otc are both set and differ; set flow.activation-code only.");
    }
    if (!set && legacySet) {
      LOGGER.warn("flow.otc (FLOW_OTC) is deprecated; use flow.activation-code (FLOW_ACTIVATION_CODE).");
    }
    this.code = set ? code : legacySet ? legacyCode : "";
    if (!this.code.isEmpty() && this.code.length() < MIN_LENGTH) {
      throw new IllegalStateException(
          "flow.activation-code must be at least " + MIN_LENGTH + " characters.");
    }
    if (this.code.isEmpty() && !adminClaim.configured()) {
      LOGGER.warn(
          "No activation code is set, so the first admin cannot be created. Set FLOW_ACTIVATION_CODE"
              + " (at least {} characters), or configure an admin claim.",
          MIN_LENGTH);
    }
  }

  /** Whether {@code candidate} is the activation code, compared in constant time; false when none is set. */
  public boolean matches(String candidate) {
    return !code.isEmpty()
        && candidate != null
        && MessageDigest.isEqual(
            candidate.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
  }
}
