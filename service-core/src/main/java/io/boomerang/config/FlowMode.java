package io.boomerang.config;

import org.springframework.core.env.Environment;

/**
 * The three deployable shapes of the merged {@code service-core} module (DD-02/H6).
 *
 * <ul>
 *   <li>{@link #FULL} - flow + engine + integrations, the default (and currently only shipped)
 *       shape.
 *   <li>{@link #ENGINE} - engine-only, no flow API/integrations surface.
 *   <li>{@link #STANDALONE} - a single self-contained instance (no external engine/flow split).
 * </ul>
 *
 * <p>Driven by the {@code flow.mode} property (missing or blank = {@link #FULL}). Beans that
 * should only load in a subset of modes use {@link ConditionalOnFlowMode} rather than resolving
 * this enum by hand.
 */
public enum FlowMode {
  FULL("full"),
  ENGINE("engine"),
  STANDALONE("standalone");

  private final String value;

  FlowMode(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Resolves the configured {@code flow.mode} property against the given environment. Missing or
   * blank resolves to {@link #FULL}.
   *
   * @throws IllegalArgumentException if the property is set to something other than {@code
   *     full}, {@code engine} or {@code standalone} (case-insensitive)
   */
  public static FlowMode resolve(Environment environment) {
    return fromValue(environment.getProperty("flow.mode"));
  }

  /** Parses a raw {@code flow.mode} property value; missing/blank resolves to {@link #FULL}. */
  public static FlowMode fromValue(String value) {
    if (value == null || value.isBlank()) {
      return FULL;
    }
    String trimmed = value.trim();
    for (FlowMode mode : values()) {
      if (mode.value.equalsIgnoreCase(trimmed)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "Unrecognized flow.mode '" + value + "'. Expected one of: full, engine, standalone.");
  }

  @Override
  public String toString() {
    return value;
  }
}
