package io.boomerang.core.security;

import io.boomerang.config.FlowMode;
import org.springframework.core.env.Environment;

/**
 * A5/H6, simplified 2026-08-15 (v5 is the major - the legacy per-half alias pair is dropped): the
 * single {@code flow.security.enabled} property gates BOTH security halves - authentication (the
 * filter chain, {@link SecurityConfiguration}/{@link SecurityDisabledConfiguration}) and
 * authorization ({@link SecurityInterceptorConfiguration}). Its default derives from {@code
 * flow.mode}: {@code standalone} = enabled (today's default), {@code engine} = disabled.
 *
 * <p>This keeps every currently-deployed configuration byte-identical: nothing changes unless
 * {@code flow.mode} or {@code flow.security.enabled} is newly adopted.
 */
public final class FlowSecurityProperties {

  static final String UNIFIED_PROPERTY = "flow.security.enabled";

  private FlowSecurityProperties() {}

  /** Whether security (both the authentication filter chain and the authorization interceptor) should be active. */
  public static boolean isSecurityEnabled(Environment environment) {
    String unified = environment.getProperty(UNIFIED_PROPERTY);
    if (unified != null) {
      return Boolean.parseBoolean(unified);
    }
    return FlowMode.resolve(environment) == FlowMode.STANDALONE;
  }

  /**
   * Whether only a Flow-minted token may identify a caller: engine mode with security on. No
   * authenticating proxy stands in front of an engine, so an unsigned JWT, the shared Basic
   * password or a forwarded identity header would let any caller name itself. A session cookie is
   * still accepted - it carries a Flow-minted session token, as the verified sign-in mints it.
   */
  public static boolean isTokenOnly(Environment environment) {
    return isSecurityEnabled(environment) && FlowMode.resolve(environment) == FlowMode.ENGINE;
  }
}
