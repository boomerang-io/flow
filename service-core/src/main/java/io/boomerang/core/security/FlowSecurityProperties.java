package io.boomerang.core.security;

import io.boomerang.config.FlowMode;
import org.springframework.core.env.Environment;

/**
 * A5/H6: resolves the two security halves - authentication ({@code flow.auth.enabled}, gates
 * {@link SecurityConfiguration}/{@link SecurityDisabledConfiguration}) and authorization ({@code
 * flow.authorization.enabled}, gates {@link SecurityInterceptorConfiguration}) - through the
 * single unified {@code flow.security.enabled} property, whose own default derives from {@code
 * flow.mode}: {@code full} = enabled (today's default), {@code engine}/{@code standalone} =
 * disabled.
 *
 * <p>Resolution order per half, highest priority first:
 *
 * <ol>
 *   <li>The legacy per-half property, if explicitly set ({@code flow.auth.enabled} /
 *       {@code flow.authorization.enabled}) - kept for backward compatibility, deprecated (see
 *       {@link SecurityPropertiesDeprecationLogger}).
 *   <li>{@code flow.security.enabled}, if explicitly set.
 *   <li>The {@link FlowMode} derived default ({@code full} -&gt; enabled).
 * </ol>
 *
 * <p>This keeps every currently-deployed configuration byte-identical: nothing changes unless
 * {@code flow.mode} or {@code flow.security.enabled} is newly adopted.
 */
public final class FlowSecurityProperties {

  static final String LEGACY_AUTH_PROPERTY = "flow.auth.enabled";
  static final String LEGACY_AUTHORIZATION_PROPERTY = "flow.authorization.enabled";
  static final String UNIFIED_PROPERTY = "flow.security.enabled";

  private FlowSecurityProperties() {}

  /** Whether the authentication half (the {@code flow.auth.*} filter chain) should be active. */
  public static boolean isAuthEnabled(Environment environment) {
    String legacy = environment.getProperty(LEGACY_AUTH_PROPERTY);
    if (legacy != null) {
      return Boolean.parseBoolean(legacy);
    }
    return isSecurityEnabledByDefault(environment);
  }

  /** Whether the authorization half (the {@link SecurityInterceptor}) should be active. */
  public static boolean isAuthorizationEnabled(Environment environment) {
    String legacy = environment.getProperty(LEGACY_AUTHORIZATION_PROPERTY);
    if (legacy != null) {
      return Boolean.parseBoolean(legacy);
    }
    return isSecurityEnabledByDefault(environment);
  }

  private static boolean isSecurityEnabledByDefault(Environment environment) {
    String unified = environment.getProperty(UNIFIED_PROPERTY);
    if (unified != null) {
      return Boolean.parseBoolean(unified);
    }
    return FlowMode.resolve(environment) == FlowMode.FULL;
  }
}
