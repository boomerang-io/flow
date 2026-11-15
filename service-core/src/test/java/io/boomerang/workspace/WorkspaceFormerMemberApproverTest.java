package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.entity.ApproverGroupEntity;
import io.boomerang.workspace.model.ApproverGroupRequest;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A member removed from a workspace, or leaving it, is taken out of each of its approver groups,
 * so a group never names someone outside the workspace. Groups of other workspaces keep them.
 */
class WorkspaceFormerMemberApproverTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenService tokenService;
  @Autowired private ApproverGroupRepository approverGroupRepository;

  private String suffix;

  @BeforeEach
  void uniqueSuffix() {
    suffix = UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void aRemovedMemberLeavesEveryApproverGroupOfTheWorkspace() {
    String workspace = createWorkspace("approver-removed");
    String other = createWorkspace("approver-removed-other");
    UserEntity removed = join(workspace, "removed-" + suffix + "@example.com");
    UserEntity stays = join(workspace, "stays-" + suffix + "@example.com");
    join(other, removed.getEmail());
    groups(workspace, List.of("first", "second"), List.of(removed.getId(), stays.getId()));
    groups(other, List.of("elsewhere"), List.of(removed.getId()));

    workspaceService.removeMembers(workspace, List.of(member(removed.getId())));

    assertThat(approversOf(workspace))
        .hasSize(2)
        .allSatisfy(approvers -> assertThat(approvers).containsExactly(stays.getId()));
    assertThat(approversOf(other)).containsExactly(List.of(removed.getId()));
  }

  @Test
  void aMemberWhoLeavesLeavesEveryApproverGroupOfTheWorkspace() {
    String workspace = createWorkspace("approver-left");
    UserEntity leaving = join(workspace, "leaving-" + suffix + "@example.com");
    UserEntity stays = join(workspace, "stays-" + suffix + "@example.com");
    groups(workspace, List.of("only"), List.of(leaving.getId(), stays.getId()));

    Authentication admin = SecurityContextHolder.getContext().getAuthentication();
    signInAs(leaving);
    workspaceService.leave(workspace);
    SecurityContextHolder.getContext().setAuthentication(admin);

    assertThat(approversOf(workspace)).containsExactly(List.of(stays.getId()));
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name + "-" + suffix);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  // An editor who has signed in, so removing them keeps their record.
  private UserEntity join(String workspace, String email) {
    WorkspaceMember member = new WorkspaceMember();
    member.setEmail(email);
    member.setRole("editor");
    WorkspaceRequest request = new WorkspaceRequest();
    request.setMembers(List.of(member));
    workspaceService.patch(workspace, request);
    userService.getAndRegisterUser(email, Optional.empty(), Optional.empty(), Optional.empty(), false);
    return userRepository.findByEmail(email);
  }

  private void groups(String workspace, List<String> names, List<String> approvers) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setApproverGroups(
        names.stream()
            .map(
                name -> {
                  ApproverGroupRequest group = new ApproverGroupRequest();
                  group.setName(name);
                  group.setApprovers(approvers);
                  return group;
                })
            .toList());
    workspaceService.patch(workspace, request);
    assertThat(approversOf(workspace)).hasSize(names.size()).allSatisfy(
        stored -> assertThat(stored).containsExactlyInAnyOrderElementsOf(approvers));
  }

  private List<List<String>> approversOf(String workspace) {
    return approverGroupRepository
        .findByIdIn(
            relationshipService.refsInWorkspace(
                RelationshipType.APPROVERGROUP, workspace, Optional.empty()))
        .stream()
        .map(ApproverGroupEntity::getApprovers)
        .toList();
  }

  private void signInAs(UserEntity user) {
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(user.getId());
    principal.setPermissions(tokenService.resolvePermissionsForUser(user));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(user.getId(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static WorkspaceMember member(String id) {
    WorkspaceMember member = new WorkspaceMember();
    member.setId(id);
    return member;
  }
}
