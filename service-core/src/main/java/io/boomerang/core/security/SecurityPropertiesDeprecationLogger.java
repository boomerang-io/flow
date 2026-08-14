package io.boomerang.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A5/H6: {@code flow.auth.enabled} and {@code flow.authorization.enabled} are deprecated in
 * favour of the unified {@code flow.security.enabled} (see {@link FlowSecurityProperties}). Warns
 * once at boot, per legacy property, when it is explicitly set - visibility only, the legacy
 * value still wins for its half until the alias is removed in a later track.
 */
@Configuration
public class SecurityPropertiesDeprecationLogger {

  private static final Logger LOGGER = LogManager.getLogger();

  public SecurityPropertiesDeprecationLogger(Environment environment) {
    if (environment.containsProperty(FlowSecurityProperties.LEGACY_AUTH_PROPERTY)) {
      LOGGER.warn(
          "{} is deprecated - set {} instead. The legacy property still takes precedence for"
              + " authentication until it is removed.",
          FlowSecurityProperties.LEGACY_AUTH_PROPERTY,
          FlowSecurityProperties.UNIFIED_PROPERTY);
    }
    if (environment.containsProperty(FlowSecurityProperties.LEGACY_AUTHORIZATION_PROPERTY)) {
      LOGGER.warn(
          "{} is deprecated - set {} instead. The legacy property still takes precedence for"
              + " authorization until it is removed.",
          FlowSecurityProperties.LEGACY_AUTHORIZATION_PROPERTY,
          FlowSecurityProperties.UNIFIED_PROPERTY);
    }
  }
}
