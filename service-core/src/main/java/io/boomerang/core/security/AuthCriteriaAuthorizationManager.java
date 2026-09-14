package io.boomerang.core.security;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;

/**
 * Enforces {@link AuthCriteria} through Spring Security's native method security, replacing the
 * retired {@code SecurityInterceptor} {@code HandlerInterceptor}. Same three checks in the same
 * order, on the same identity source ({@link IdentityService#getCurrentIdentity()}), counted on
 * the same {@code flow.security.denied} metric with the same tags:
 *
 * <ol>
 *   <li>neither {@code @AuthCriteria} nor {@link AuthExempt} on the method or its class - denied
 *       (deny by default; {@code @AuthExempt} is the explicit opt-out)
 *   <li>no identity - denied
 *   <li>the token's {@link AuthScope} is not one of {@code assignableScopes} - denied
 *   <li>no grant matches {@code (**|resource)/(**|action)} - denied
 * </ol>
 *
 * <p>The one behavioural fix: the annotation lookup tries the invoked {@link Method} FIRST, then
 * falls back to its declaring class. {@code @AuthCriteria} has always declared {@code
 * ElementType.TYPE} as a valid target, but the retired interceptor only ever read {@code
 * handlerMethod.getMethod().getAnnotation()}, so a class-level placement was silently ignored. No
 * endpoint currently places it at class level, so this is a pure bugfix, not a behaviour change to
 * any annotated method today.
 *
 * <p>Registered only when {@code flow.security.enabled=true} ({@link
 * MethodSecurityConfiguration}), mirroring the retired interceptor's own gating - with security
 * off, every request already carries the synthetic global admin identity (decision 0032), which
 * passes every check below unconditionally, exactly as before.
 *
 * <p><b>401 vs 403.</b> The interceptor infrastructure turns a denied {@link AuthorizationDecision}
 * into one {@code AuthorizationDeniedException} (a 403) for every reason, but the retired
 * interceptor answered "no identity" and "unassignable scope" with 401 and only a permission
 * mismatch with 403. To keep that split, the first two checks throw {@link
 * AuthenticationCredentialsNotFoundException} directly (an {@code AuthenticationException}) rather
 * than returning a decision; {@code AuthenticationFilter}'s exception handling (which already wraps
 * this whole call) routes it to the same 401 entry point as any other authentication failure. Only
 * the permission-mismatch case returns a denied decision, which surfaces as 403.
 */
public class AuthCriteriaAuthorizationManager implements AuthorizationManager<MethodInvocation> {

  private static final Logger LOGGER = LogManager.getLogger();

  private final IdentityService identityService;
  private final MeterRegistry meterRegistry;

  public AuthCriteriaAuthorizationManager(IdentityService identityService, MeterRegistry meterRegistry) {
    this.identityService = identityService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public AuthorizationDecision authorize(
      Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
    Method method = invocation.getMethod();
    // Method-level annotations win over class-level ones, whichever kind each is.
    AuthCriteria authCriteria = AnnotatedElementUtils.findMergedAnnotation(method, AuthCriteria.class);
    if (authCriteria == null) {
      if (AnnotatedElementUtils.findMergedAnnotation(method, AuthExempt.class) != null) {
        return new AuthorizationDecision(true);
      }
      Class<?> declaringClass = method.getDeclaringClass();
      authCriteria = AnnotatedElementUtils.findMergedAnnotation(declaringClass, AuthCriteria.class);
    }
    if (authCriteria == null) {
      if (AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), AuthExempt.class)
          != null) {
        return new AuthorizationDecision(true);
      }
      // Deny by default. A REST handler with neither annotation has had no authorization
      // decision made for it; the retired interceptor let it through with a WARN, which is how a
      // route could ship open by omission. AuthCriteriaCoverageTest fails the build first.
      LOGGER.error(
          "AuthCriteriaAuthorizationManager - {}.{} carries neither @AuthCriteria nor @AuthExempt."
              + " Denied.",
          method.getDeclaringClass().getSimpleName(),
          method.getName());
      meterRegistry
          .counter("flow.security.denied", "resource", "unannotated", "action", "unannotated",
              "type", "n/a")
          .increment();
      return new AuthorizationDecision(false);
    }

    Token accessToken = identityService.getCurrentIdentity();
    if (accessToken == null) {
      LOGGER.error(
          "AuthCriteriaAuthorizationManager - mismatch between AuthN and AuthZ. A permitAll route"
              + " has an AuthCriteria.");
      throw new AuthenticationCredentialsNotFoundException("No identity established");
    }

    AuthScope[] assignableScopes = authCriteria.assignableScopes();
    if (!Arrays.asList(assignableScopes).contains(accessToken.getType())) {
      LOGGER.error(
          "AuthCriteriaAuthorizationManager - Unauthorized Assigned Scope. Needed: {}, Provided:"
              + " {}",
          Arrays.toString(assignableScopes),
          accessToken.getType());
      count(authCriteria, accessToken);
      throw new AuthenticationCredentialsNotFoundException("Token scope not assignable");
    }

    PermissionResource requiredScope = authCriteria.resource();
    PermissionAction requiredAccess = authCriteria.action();
    String requiredRegex =
        "(\\*{2}|" + requiredScope.getLabel() + ")\\/(\\*{2}|" + requiredAccess.getLabel() + ")";
    boolean permitted =
        accessToken.getPermissions().stream()
            .anyMatch(p -> p.getActions().stream().anyMatch(a -> a.matches(requiredRegex)));
    if (!permitted) {
      LOGGER.warn(
          "AuthCriteriaAuthorizationManager - denied principal: {}, token type: {}, required:"
              + " {}/{}",
          accessToken.getPrincipal(),
          accessToken.getType(),
          requiredScope.getLabel(),
          requiredAccess.getLabel());
      count(authCriteria, accessToken);
      return new AuthorizationDecision(false);
    }
    return new AuthorizationDecision(true);
  }

  private void count(AuthCriteria authCriteria, Token accessToken) {
    meterRegistry
        .counter(
            "flow.security.denied",
            "resource",
            authCriteria.resource().getLabel(),
            "action",
            authCriteria.action().getLabel(),
            "type",
            accessToken.getType().toString())
        .increment();
  }
}
