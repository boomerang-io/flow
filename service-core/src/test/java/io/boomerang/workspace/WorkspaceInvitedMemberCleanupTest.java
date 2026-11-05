package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Adding a member by an email nobody uses stores an inactive user, which admits that person when
 * they first sign in. Once they belong to no workspace and still have not signed in, removing
 * them (or deleting the workspace) deletes that record, so a mistyped address leaves nothing
 * behind. Anyone who has signed in, or still belongs to a workspace, is kept.
 */
class WorkspaceInvitedMemberCleanupTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;

  private String suffix;

  @BeforeEach
  void uniqueSuffix() {
    suffix = UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void removingAMemberWhoNeverSignedInDeletesTheirRecord() {
    String workspace = createWorkspace("invite-removed");
    UserEntity invited = invite(workspace, "typo-" + suffix + "@example.com");
    assertThat(invited.getStatus()).isEqualTo(UserStatus.inactive);

    workspaceService.removeMembers(workspace, List.of(member(invited.getId())));

    assertThat(userRepository.findById(invited.getId())).isEmpty();
    assertThat(relationshipService.doesSlugOrRefExistForType(RelationshipType.USER, invited.getId()))
        .isFalse();
  }

  @Test
  void removingAMemberWhoHasSignedInKeepsTheirRecord() {
    String workspace = createWorkspace("invite-signed-in");
    UserEntity invited = invite(workspace, "grace-" + suffix + "@example.com");
    userService.getAndRegisterUser(
        invited.getEmail(), Optional.empty(), Optional.empty(), Optional.empty(), false);

    workspaceService.removeMembers(workspace, List.of(member(invited.getId())));

    assertThat(userRepository.findById(invited.getId()))
        .get()
        .extracting(UserEntity::getStatus)
        .isEqualTo(UserStatus.active);
  }

  @Test
  void removingAMemberStillInAnotherWorkspaceKeepsTheirRecord() {
    String first = createWorkspace("invite-first");
    String second = createWorkspace("invite-second");
    UserEntity invited = invite(first, "both-" + suffix + "@example.com");
    invite(second, invited.getEmail());

    workspaceService.removeMembers(first, List.of(member(invited.getId())));

    assertThat(userRepository.findById(invited.getId())).isPresent();
  }

  @Test
  void deletingAWorkspaceDeletesItsMembersWhoNeverSignedIn() {
    String workspace = createWorkspace("invite-deleted");
    UserEntity invited = invite(workspace, "gone-" + suffix + "@example.com");

    workspaceService.delete(workspace);

    assertThat(userRepository.findById(invited.getId())).isEmpty();
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name + "-" + suffix);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private UserEntity invite(String workspace, String email) {
    WorkspaceMember member = new WorkspaceMember();
    member.setEmail(email);
    member.setRole("editor");
    WorkspaceRequest request = new WorkspaceRequest();
    request.setMembers(List.of(member));
    workspaceService.patch(workspace, request);
    return userRepository.findByEmail(email);
  }

  private static WorkspaceMember member(String id) {
    WorkspaceMember member = new WorkspaceMember();
    member.setId(id);
    return member;
  }
}
