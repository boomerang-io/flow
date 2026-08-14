package io.boomerang.security;

import io.boomerang.core.model.Token;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/*
 * Interceptor for AuthScope protected controller methods
 *
 * Presumes endpoint has been through the AuthFilter and SecurityContext is loaded
 */
public class SecurityInterceptor implements HandlerInterceptor {

  private static final Logger LOGGER = LogManager.getLogger();

  private IdentityService identityService;
  private MeterRegistry meterRegistry;

  public SecurityInterceptor(IdentityService identityService, MeterRegistry meterRegistry) {
    this.identityService = identityService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (handler instanceof HandlerMethod) {
      LOGGER.debug("In SecurityInterceptor()");
      HandlerMethod handlerMethod = (HandlerMethod) handler;
      AuthCriteria authCriteria = handlerMethod.getMethod().getAnnotation(AuthCriteria.class);
      if (authCriteria == null) {
        // No annotation found - route does not need authZ
        LOGGER.warn("SecurityInterceptor - No AuthCriteria provided. Skipping Authorization.");
        return true;
      }

      // If annotation is found but CurrentScope is not then mismatch must have happened between
      // routes with AuthN and AuthZ
      if (identityService.getCurrentScope() == null) {
        LOGGER.error(
            "SecurityInterceptor - mismatch between AuthN and AuthZ. A permitAll route has an AuthScope. Scope: {}.",
            identityService.getCurrentScope());
        response.getWriter().write("");
        response.setStatus(401);
        return false;
      }

      // Check the required token scope is assigned
      // TODO should this check the assignedScope in the permission rather than the token type
      AuthScope[] assignableScopes = authCriteria.assignableScopes();
      Token accessToken = this.identityService.getCurrentIdentity();
      if (!Arrays.asList(assignableScopes).contains(accessToken.getType())) {
        LOGGER.error(
            "SecurityInterceptor - Unauthorized Assigned Scope. Needed: {}, Provided: {}",
            Arrays.toString(assignableScopes),
            accessToken.getType().toString());
        count("flow.security.denied", authCriteria, accessToken);
        response.getWriter().write("");
        response.setStatus(401);
        return false;
      }

      // Check the required access for the permission action
      // TOOD check the assignedScope
      PermissionResource requiredScope = authCriteria.resource();
      PermissionAction requiredAccess = authCriteria.action();
      String requiredRegex =
          "(\\*{2}|" + requiredScope.getLabel() + ")\\/(\\*{2}|" + requiredAccess.getLabel() + ")";
      if (!accessToken.getPermissions().stream()
          .anyMatch(p -> (p.getActions().stream().anyMatch(a -> (a.matches(requiredRegex)))))) {
        // Shadow enforcement: record the would-be denial but allow the request through
        LOGGER.warn(
            "SecurityInterceptor - would deny principal: {}, token type: {}, required: {}/{}",
            accessToken.getPrincipal(),
            accessToken.getType(),
            requiredScope.getLabel(),
            requiredAccess.getLabel());
        count("flow.security.would.deny", authCriteria, accessToken);
        return true;
      }
      return true;
    } else {
      return true;
    }
  }

  private void count(String metric, AuthCriteria authCriteria, Token accessToken) {
    meterRegistry
        .counter(
            metric,
            "resource",
            authCriteria.resource().getLabel(),
            "action",
            authCriteria.action().getLabel(),
            "type",
            accessToken.getType().toString())
        .increment();
  }
}
