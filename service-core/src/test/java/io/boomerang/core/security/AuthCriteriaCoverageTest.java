package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.WorkspaceControllerV2;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every REST handler MUST carry {@link AuthCriteria} or {@link AuthExempt}, on the method or its
 * class. {@link AuthCriteriaAuthorizationManager} denies a handler with neither at runtime; this
 * test fails the build first, naming the route, so an authorization decision is made before a route
 * ships. Static scan, no context boot: the same class filter and method match as {@link
 * MethodSecurityConfiguration.RestHandlerPointcut}.
 */
@Tag("smoke")
class AuthCriteriaCoverageTest {

  // Leaving a workspace is every member's own call: the route asks for the one permission every
  // seeded workspace role holds (a reader's is **/read), and the service removes only the caller's
  // own membership. Asking for workspace/action locked readers in, and so out of deleting their
  // account, which needs every membership gone first.
  @Test
  void leavingAWorkspaceNeedsOnlyTheReadPermissionEveryRoleHolds() throws NoSuchMethodException {
    AuthCriteria criteria =
        WorkspaceControllerV2.class.getMethod("leave", String.class).getAnnotation(AuthCriteria.class);
    assertThat(criteria.resource()).isEqualTo(PermissionResource.WORKSPACE);
    assertThat(criteria.action()).isEqualTo(PermissionAction.READ);
  }

  @Test
  void everyRestHandlerCarriesAuthCriteriaOrAuthExempt() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    List<String> unannotated = new ArrayList<>();
    int handlers = 0;
    for (var definition : scanner.findCandidateComponents("io.boomerang")) {
      Class<?> controller = Class.forName(definition.getBeanClassName());
      if (controller.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) {
        continue; // fixtures such as YamlConfigurationTest.DemoController, never served
      }
      boolean classDecided =
          AnnotatedElementUtils.hasAnnotation(controller, AuthCriteria.class)
              || AnnotatedElementUtils.hasAnnotation(controller, AuthExempt.class);
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        handlers++;
        boolean methodDecided =
            AnnotatedElementUtils.hasAnnotation(method, AuthCriteria.class)
                || AnnotatedElementUtils.hasAnnotation(method, AuthExempt.class);
        if (!methodDecided && !classDecided) {
          unannotated.add(controller.getSimpleName() + "." + method.getName());
        }
      }
    }

    assertThat(handlers).as("the scan found the controllers").isGreaterThan(50);
    assertThat(unannotated)
        .as(
            "REST handlers with neither @AuthCriteria nor @AuthExempt (add one; an exemption needs"
                + " a reason)")
        .isEmpty();
  }
}
