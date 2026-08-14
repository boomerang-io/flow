package io.boomerang.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Restricts a {@code @Configuration}/{@code @Component} (or an individual {@code @Bean} method)
 * to load only when the resolved {@link FlowMode} - property {@code flow.mode}, missing/blank
 * defaults to {@link FlowMode#FULL} - is one of the given modes.
 *
 * <p>Plain {@code @ConditionalOnProperty} can't express this: it only matches a single literal
 * property value, not "any of these modes" (an OR across modes). This is the pattern every future
 * module-root gate (per the v5 package restructure) should use rather than hand-rolling a mode
 * check.
 *
 * <p>Example - a bean that should load in both {@code full} and {@code standalone}, but not
 * {@code engine}:
 *
 * <pre>{@code
 * @Configuration
 * @ConditionalOnFlowMode({FlowMode.FULL, FlowMode.STANDALONE})
 * public class IntegrationsConfiguration { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnFlowModeCondition.class)
public @interface ConditionalOnFlowMode {

  /** The set of {@link FlowMode} values under which the annotated bean/configuration loads. */
  FlowMode[] value();
}
