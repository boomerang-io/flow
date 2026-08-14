package io.boomerang.config;

import java.util.Arrays;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@link Condition} backing {@link ConditionalOnFlowMode}. See that annotation for semantics. */
class OnFlowModeCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    MergedAnnotation<ConditionalOnFlowMode> annotation =
        metadata.getAnnotations().get(ConditionalOnFlowMode.class);
    FlowMode[] allowedModes = annotation.getEnumArray("value", FlowMode.class);
    FlowMode configuredMode = FlowMode.resolve(context.getEnvironment());
    return Arrays.asList(allowedModes).contains(configuredMode);
  }
}
