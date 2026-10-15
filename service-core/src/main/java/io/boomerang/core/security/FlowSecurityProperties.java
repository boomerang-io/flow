package io.boomerang.core.security;

import org.springframework.core.env.Environment;

/**
 * The single {@code flow.security.enabled} property gates BOTH security halves - authentication
 * (the filter chain, {@link SecurityConfiguration}/{@link SecurityDisabledConfiguration}) and
 * authorization ({@link MethodSecurityConfiguration}). Default {@code true}; an embedding product
 * or a local developer sets it to {@code false}, and every request then runs as {@link
 * UnauthenticatedGlobalToken}.
 */
public final class FlowSecurityProperties {

  static final String UNIFIED_PROPERTY = "flow.security.enabled";

  private FlowSecurityProperties() {}

  /** Whether security (both the authentication filter chain and endpoint authorization) is active. */
  public static boolean isSecurityEnabled(Environment environment) {
    return environment.getProperty(UNIFIED_PROPERTY, Boolean.class, true);
  }
}
