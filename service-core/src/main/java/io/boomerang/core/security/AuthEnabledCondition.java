package io.boomerang.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when the resolved authentication half is enabled. See {@link FlowSecurityProperties}. */
class AuthEnabledCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return FlowSecurityProperties.isAuthEnabled(context.getEnvironment());
  }
}
