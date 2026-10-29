package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * An embedding product mirrors its own records as workspaces keyed by {@code externalRef}: one
 * workspace per reference, a second create answered with the one that holds it, and a lookup by
 * the reference within the caller's access.
 */
class WorkspaceExternalRefTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSecondCreateWithTheSameExternalRefAnswersTheExistingWorkspace() {
    String ref = "tenant-" + System.nanoTime();
    Workspace first = workspaceService.create(request("mirror-a-" + System.nanoTime(), ref));
    assertThat(first.getExternalRef()).isEqualTo(ref);

    assertThatThrownBy(() -> workspaceService.create(request("mirror-b-" + System.nanoTime(), ref)))
        .isInstanceOf(BoomerangException.class)
        .satisfies(
            e -> {
              assertThat(((BoomerangException) e).getCode())
                  .isEqualTo(BoomerangError.WORKSPACE_EXTERNAL_REF_TAKEN.getCode());
              // The message names the holder; the handler formats it from this argument.
              assertThat(((BoomerangException) e).getArgs()).containsExactly(first.getName());
            });
  }

  @Test
  void aWorkspaceIsFoundByItsExternalRefWithinTheCallersAccess() {
    String ref = "tenant-" + System.nanoTime();
    Workspace mine = workspaceService.create(request("mirror-c-" + System.nanoTime(), ref));
    workspaceService.create(request("mirror-d-" + System.nanoTime(), "other-" + System.nanoTime()));
    signInAsGlobalReader();

    List<Workspace> found =
        workspaceService
            .query(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(ref))
            .getContent();
    assertThat(found).extracting(Workspace::getName).containsExactly(mine.getName());
    assertThat(
            workspaceService
                .query(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of("no-such-" + System.nanoTime()))
                .getContent())
        .isEmpty();
  }

  private static WorkspaceRequest request(String name, String externalRef) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setExternalRef(externalRef);
    return request;
  }

  private void signInAsGlobalReader() {
    String principal = "ext-reader-" + System.nanoTime();
    relationshipService.createNode(RelationshipType.USER, principal, principal, Optional.empty());
    Token token = new Token(AuthScope.global);
    token.setPrincipal(principal);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null);
    authentication.setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
