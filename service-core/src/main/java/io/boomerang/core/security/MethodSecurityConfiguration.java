package io.boomerang.core.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enforces {@link AuthCriteria} through Spring Security's native method security, replacing the
 * {@code HandlerInterceptor}-based {@code SecurityInterceptor}/{@code
 * SecurityInterceptorConfiguration} - see {@link AuthCriteriaAuthorizationManager} for the
 * enforcement logic itself.
 *
 * <p>The advisor's {@link Pointcut} matches a method OR its declaring class carrying {@code
 * @AuthCriteria} - the union is what makes class-level placement work at all; a pointcut over
 * method-level presence alone would never select a class-only-annotated controller's methods for
 * interception in the first place, no matter what the {@code AuthorizationManager} does once
 * invoked. {@code @EnableMethodSecurity} activates the AOP auto-proxying this advisor bean needs to
 * be picked up; nothing here uses {@code @PreAuthorize} or the other built-in annotations it also
 * enables.
 *
 * <p>Gated on {@code flow.security.enabled=true}, exactly like the retired interceptor
 * configuration - see {@link FlowSecurityProperties}.
 */
// A5/H6: gated via the unified flow.security.enabled resolution - see FlowSecurityProperties.
@Configuration
@Conditional(SecurityEnabledCondition.class)
@EnableMethodSecurity
public class MethodSecurityConfiguration {

  // static, per Spring Security's own guidance for custom method-security Advisor beans: it must
  // be created before the application context's other beans are eligible for AOP auto-proxying.
  // @Role(ROLE_INFRASTRUCTURE) is required too - @EnableMethodSecurity's auto-proxy creator
  // (InfrastructureAdvisorAutoProxyCreator) only ever applies advisor beans carrying that role;
  // without it this bean is registered but silently never applied to anything.
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor authCriteriaAuthorizationAdvisor(
      IdentityService identityService, MeterRegistry meterRegistry) {
    Pointcut pointcut =
        Pointcuts.union(
            AnnotationMatchingPointcut.forClassAnnotation(AuthCriteria.class),
            AnnotationMatchingPointcut.forMethodAnnotation(AuthCriteria.class));
    AuthorizationManager<MethodInvocation> manager =
        new AuthCriteriaAuthorizationManager(identityService, meterRegistry);
    return new AuthorizationManagerBeforeMethodInterceptor(pointcut, manager);
  }
}
