package io.boomerang.config;

import org.springframework.core.env.Environment;

/**
 * The two deployable shapes of the merged {@code service-core} module (DD-02/H6, re-ruled
 * 2026-08-15: the old three-mode list collapses to two - FULL merges into STANDALONE).
 *
 * <ul>
 *   <li>{@link #STANDALONE} - the complete self-contained product: workspaces, auth,
 *       integrations, schedules, everything. The default. (Running it with security off is just
 *       the product configured that way - it is no longer a distinct mode.)
 *   <li>{@link #ENGINE} - the embedded headless execution subset, no workspace/auth/integrations
 *       surface.
 * </ul>
 *
 * <p>Driven by the {@code flow.mode} property (missing or blank = {@link #STANDALONE}). Beans
 * that should only load in a subset of modes use {@link ConditionalOnFlowMode} rather than
 * resolving this enum by hand.
 */
public enum FlowMode {
  STANDALONE("standalone"),
  ENGINE("engine");

  private final String value;

  FlowMode(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Resolves the configured {@code flow.mode} property against the given environment. Missing or
   * blank resolves to {@link #STANDALONE}.
   *
   * @throws IllegalArgumentException if the property is set to something other than {@code
   *     standalone} or {@code engine} (case-insensitive)
   */
  public static FlowMode resolve(Environment environment) {
    return fromValue(environment.getProperty("flow.mode"));
  }

  /**
   * Parses a raw {@code flow.mode} property value; missing/blank resolves to {@link #STANDALONE}.
   */
  public static FlowMode fromValue(String value) {
    if (value == null || value.isBlank()) {
      return STANDALONE;
    }
    String trimmed = value.trim();
    for (FlowMode mode : values()) {
      if (mode.value.equalsIgnoreCase(trimmed)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "Unrecognized flow.mode '" + value + "'. Expected one of: standalone, engine.");
  }

  @Override
  public String toString() {
    return value;
  }
}
