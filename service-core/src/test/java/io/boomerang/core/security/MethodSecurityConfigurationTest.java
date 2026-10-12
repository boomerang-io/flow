package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDeniedException;

/**
 * Proves the AOP wiring itself, not just {@link AuthCriteriaAuthorizationManager}'s logic in
 * isolation: a real Spring context with {@link MethodSecurityConfiguration} registered, a bean
 * carrying {@code @AuthCriteria} at CLASS level with an unannotated method, and a real (proxied)
 * method call through it - no Mongo, no web layer, no Testcontainers.
 */
class MethodSecurityConfigurationTest {

  private AnnotationConfigApplicationContext context;

  @AfterEach
  void closeContext() {
    if (context != null) {
      context.close();
    }
  }

  @AuthCriteria(
      assignableScopes = {AuthScope.global},
      resource = PermissionResource.WORKFLOW,
      action = PermissionAction.READ)
  public static class ClassLevelProtectedBean {
    public String unannotatedMethod() {
      return "called";
    }
  }

  @Configuration
  static class TestBeans {
    @Bean
    IdentityService identityService() {
      return mock(IdentityService.class);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ClassLevelProtectedBean classLevelProtectedBean() {
      return new ClassLevelProtectedBean();
    }
  }

  private AnnotationConfigApplicationContext bootContext() {
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources().addFirst(
        new org.springframework.core.env.MapPropertySource(
            "test", java.util.Map.of("flow.security.enabled", "true")));
    ctx.register(MethodSecurityConfiguration.class, TestBeans.class);
    ctx.refresh();
    return ctx;
  }

  @Test
  void aClassLevelAuthCriteriaIsEnforcedThroughARealProxiedCall() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    when(identityService.getCurrentIdentity()).thenReturn(new Token(AuthScope.user));
    ClassLevelProtectedBean bean = context.getBean(ClassLevelProtectedBean.class);
    assertThat(AopUtils.isAopProxy(bean))
        .as("the auto-proxy creator must have wrapped this bean for the advisor to ever run")
        .isTrue();

    assertThatThrownBy(bean::unannotatedMethod)
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
  }

  @Test
  void aPermissionMismatchThroughTheRealProxyDeniesWithA403WorthyException() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    Token token = new Token(AuthScope.global);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "admin", List.of("workflow/write"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);
    ClassLevelProtectedBean bean = context.getBean(ClassLevelProtectedBean.class);

    assertThatThrownBy(bean::unannotatedMethod).isInstanceOf(AuthorizationDeniedException.class);
  }

  @Test
  void aGrantedCallerReachesTheRealMethod() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    Token token = new Token(AuthScope.global);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "admin", List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);
    ClassLevelProtectedBean bean = context.getBean(ClassLevelProtectedBean.class);

    assertThat(bean.unannotatedMethod()).isEqualTo("called");
  }
}
