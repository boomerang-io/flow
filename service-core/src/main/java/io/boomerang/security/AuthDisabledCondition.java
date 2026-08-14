package io.boomerang.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the resolved authentication half is disabled - the complement of {@link
 * AuthEnabledCondition}. See {@link FlowSecurityProperties}.
 */
class AuthDisabledCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !FlowSecurityProperties.isAuthEnabled(context.getEnvironment());
  }
}
