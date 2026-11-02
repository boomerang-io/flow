package io.boomerang.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.AuthExempt;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/**
 * Each operation in the contract states the permission its handler is checked against, read by
 * the rule the authorization manager enforces: a method's annotation wins over its class's,
 * whichever kind each is, and an exempt handler states why.
 */
class OpenApiPermissionTest {

  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.user})
  static class Handlers {

    @AuthCriteria(
        action = PermissionAction.ACTION,
        resource = PermissionResource.WORKFLOWRUN,
        assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
    public void onMethod() {}

    public void fromClass() {}

    @AuthExempt(reason = "Verified by the sender's signature.")
    public void exempt() {}
  }

  private static Operation describe(String method, String description) throws Exception {
    Operation operation = new Operation().description(description);
    return OpenApiConfiguration.describePermission(
        operation, new HandlerMethod(new Handlers(), Handlers.class.getMethod(method)));
  }

  @Test
  void aMethodsCriteriaIsStatedAsTheExtensionAndASentence() throws Exception {
    Operation operation = describe("onMethod", null);

    assertThat(operation.getExtensions())
        .containsEntry(
            OpenApiConfiguration.PERMISSION_EXTENSION,
            Map.of(
                "permission", "workflowrun/action",
                "tokens", List.of("global", "key", "user", "session")));
    assertThat(operation.getDescription())
        .isEqualTo(
            "Requires the `workflowrun/action` permission, from a global, key, user, session token.");
  }

  @Test
  void aHandlerWithoutItsOwnTakesItsClasssCriteriaAndKeepsItsDescription() throws Exception {
    Operation operation = describe("fromClass", "Reads a workflow.");

    assertThat(operation.getExtensions())
        .containsEntry(
            OpenApiConfiguration.PERMISSION_EXTENSION,
            Map.of("permission", "workflow/read", "tokens", List.of("global", "user")));
    assertThat(operation.getDescription())
        .isEqualTo(
            "Reads a workflow.\n\nRequires the `workflow/read` permission, from a global, user token.");
  }

  @Test
  void aMethodsExemptionOverridesItsClasssCriteriaAndStatesWhy() throws Exception {
    Operation operation = describe("exempt", null);

    assertThat(operation.getExtensions())
        .containsEntry(
            OpenApiConfiguration.PERMISSION_EXTENSION,
            Map.of("exempt", "Verified by the sender's signature."));
    assertThat(operation.getDescription())
        .isEqualTo("No permission check: Verified by the sender's signature.");
  }
}
