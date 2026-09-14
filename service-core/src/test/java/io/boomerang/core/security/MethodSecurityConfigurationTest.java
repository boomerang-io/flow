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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

  /** A REST handler with neither annotation - what a route added by omission looks like. */
  @RestController
  public static class UnannotatedController {
    @GetMapping("/unannotated")
    public String handler() {
      return "served";
    }
  }

  /** The explicit opt-out. */
  @RestController
  public static class ExemptController {
    @GetMapping("/exempt")
    @AuthExempt(reason = "test fixture: public by design")
    public String handler() {
      return "served";
    }
  }

  /** A class-level exemption with one method that still asks for authorization. */
  @RestController
  @AuthExempt(reason = "test fixture: the controller is public except where a method says otherwise")
  public static class ExemptControllerWithProtectedMethod {
    @GetMapping("/open")
    public String open() {
      return "served";
    }

    @GetMapping("/guarded")
    @AuthCriteria(
        assignableScopes = {AuthScope.global},
        resource = PermissionResource.WORKFLOW,
        action = PermissionAction.READ)
    public String guarded() {
      return "served";
    }
  }

  @Configuration
  static class TestBeans {
    @Bean
    UnannotatedController unannotatedController() {
      return new UnannotatedController();
    }

    @Bean
    ExemptController exemptController() {
      return new ExemptController();
    }

    @Bean
    ExemptControllerWithProtectedMethod exemptControllerWithProtectedMethod() {
      return new ExemptControllerWithProtectedMethod();
    }

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

  @Test
  void aRestHandlerWithNeitherAnnotationIsDeniedByDefault() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    Token token = new Token(AuthScope.global);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "admin", List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);
    UnannotatedController bean = context.getBean(UnannotatedController.class);
    assertThat(AopUtils.isAopProxy(bean))
        .as("every io.boomerang @RestController is intercepted, annotated or not")
        .isTrue();

    // Even a caller holding **/** is denied: no authorization decision exists for the route.
    assertThatThrownBy(bean::handler).isInstanceOf(AuthorizationDeniedException.class);
    assertThat(
            context
                .getBean(MeterRegistry.class)
                .counter("flow.security.denied", "resource", "unannotated", "action", "unannotated",
                    "type", "n/a")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void anExemptHandlerIsServedWithoutAnyIdentity() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    when(identityService.getCurrentIdentity()).thenReturn(null);

    assertThat(context.getBean(ExemptController.class).handler()).isEqualTo("served");
  }

  @Test
  void aMethodLevelAuthCriteriaWinsOverAClassLevelExemption() {
    context = bootContext();
    IdentityService identityService = context.getBean(IdentityService.class);
    when(identityService.getCurrentIdentity()).thenReturn(new Token(AuthScope.user));
    ExemptControllerWithProtectedMethod bean =
        context.getBean(ExemptControllerWithProtectedMethod.class);

    assertThat(bean.open()).isEqualTo("served");
    assertThatThrownBy(bean::guarded).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
  }
}
