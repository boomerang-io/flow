package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A workflow version records who made it, as a task version does: a signed-in user's id is
 * stored, and the changelog reads it back as the user's name.
 */
class WorkflowChangelogAuthorTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;

  @Test
  void aSignedInUserAuthorsTheVersionAndTheChangelogNamesThem() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest workspaceRequest = new WorkspaceRequest();
    workspaceRequest.setName("changelog-author-" + suffix);
    workspaceRequest.setDisplayName(workspaceRequest.getName());
    String workspace = workspaceService.create(workspaceRequest).getName();

    UserEntity user = new UserEntity();
    user.setName("grace@example.com");
    user.setEmail("grace-" + suffix + "@example.com");
    user.setDisplayName("Grace Hopper");
    String userId = userRepository.save(user).getId();
    // Create reaches the workspace through membership, as a session user does in production.
    relationshipService.createNode(RelationshipType.USER, userId, user.getName(), Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER, userId, RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, workspace, Optional.empty());
    signInAs(userId);

    Workflow request = new Workflow();
    request.setName("changelog-author-" + suffix);
    Workflow created = workflowService.create(workspace, request);

    assertThat(workflowService.get(workspace, created.getName(), Optional.empty(), false).getChangelog().getAuthor())
        .isEqualTo(userId);
    assertThat(workflowService.changelog(workspace, created.getName()).getBody())
        .extracting(ChangeLogVersion::getAuthor)
        .containsExactly("Grace Hopper");
  }

  @Test
  void aUserWithoutADisplayNameReadsByName() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest workspaceRequest = new WorkspaceRequest();
    workspaceRequest.setName("changelog-noname-" + suffix);
    workspaceRequest.setDisplayName(workspaceRequest.getName());
    String workspace = workspaceService.create(workspaceRequest).getName();

    // A provider that sends no display name, as the local identity picker does.
    UserEntity user = new UserEntity();
    user.setName("ada-" + suffix + "@example.com");
    user.setEmail(user.getName());
    String userId = userRepository.save(user).getId();
    relationshipService.createNode(RelationshipType.USER, userId, user.getName(), Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER, userId, RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, workspace, Optional.empty());
    signInAs(userId);

    Workflow request = new Workflow();
    request.setName("changelog-noname-" + suffix);
    Workflow created = workflowService.create(workspace, request);

    assertThat(workflowService.changelog(workspace, created.getName()).getBody())
        .extracting(ChangeLogVersion::getAuthor)
        .containsExactly(user.getName());
  }

  private static void signInAs(String userId) {
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userId);
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
