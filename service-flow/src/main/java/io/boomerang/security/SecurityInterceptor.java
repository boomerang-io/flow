package io.boomerang.security;

import io.boomerang.core.model.Token;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
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

  public SecurityInterceptor(IdentityService identityService) {
    this.identityService = identityService;
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
      LOGGER.info(
          "SecurityInterceptor - Permission needed: {}, Provided: {}",
          requiredScope.getLabel() + "/" + requiredAccess.getLabel(),
          accessToken.getPermissions().toString());
      if (!accessToken.getPermissions().stream()
          .anyMatch(p -> (p.getActions().stream().anyMatch(a -> (a.matches(requiredRegex)))))) {
        LOGGER.error("SecurityInterceptor - Unauthorized Permission.");
        // TODO set this to return false
        //      response.getWriter().write("");
        //      response.setStatus(401);
        return true;
      }
      return true;
    } else {
      return true;
    }
  }
}
