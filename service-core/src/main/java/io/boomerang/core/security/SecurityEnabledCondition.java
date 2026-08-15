package io.boomerang.core.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code flow.security.enabled} (resolved via {@link FlowSecurityProperties}) is
 * enabled. Gates both security halves - the authentication filter chain ({@link
 * SecurityConfiguration}) and the authorization interceptor ({@link
 * SecurityInterceptorConfiguration}) - since 2026-08-15 they key off the same single property.
 */
class SecurityEnabledCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return FlowSecurityProperties.isSecurityEnabled(context.getEnvironment());
  }
}
