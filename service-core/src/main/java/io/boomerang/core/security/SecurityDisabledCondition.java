package io.boomerang.core.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when security is disabled - the complement of {@link SecurityEnabledCondition}. See
 * {@link FlowSecurityProperties}.
 */
class SecurityDisabledCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !FlowSecurityProperties.isSecurityEnabled(context.getEnvironment());
  }
}
