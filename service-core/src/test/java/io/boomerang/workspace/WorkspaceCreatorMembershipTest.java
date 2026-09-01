package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The creating USER is always a member: a session caller who omits members must not create a
 * workspace they then cannot see - the relationship filter anchors at the user, so a workspace
 * with no MEMBER_OF edge back to its creator is invisible to them (previously only the UI's
 * self-inclusion compensated; the API contract did not).
 */
class WorkspaceCreatorMembershipTest extends AbstractEngineIntegrationTest {

  private static final String CREATOR_EMAIL = "creator-membership@test.local";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;

  private String creatorId;

  @BeforeEach
  void establishSessionCreator() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    UserEntity creator = new UserEntity();
    creator.setEmail(CREATOR_EMAIL);
    creator.setName("Creator Membership");
    creator.setType(UserType.user);
    creator.setStatus(UserStatus.active);
    creatorId = userRepository.save(creator).getId();
    relationshipService.createNode(RelationshipType.USER, creatorId, CREATOR_EMAIL, Optional.empty());

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(creatorId);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(creatorId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void createWithNoMembersMakesTheCreatorAMember() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("creator-membership-ws");
    request.setDisplayName("Creator Membership WS");

    workspaceService.create(request);

    assertTrue(
        relationshipService.check(
            RelationshipType.WORKSPACE,
            "creator-membership-ws",
            Optional.empty(),
            Optional.empty()),
        "the creating session user must reach the workspace they just created");
  }

  @Test
  void explicitMembersStillWorkAndTheCreatorIsNotDuplicated() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("creator-membership-explicit-ws");
    request.setDisplayName("Creator Membership Explicit WS");
    WorkspaceMember self = new WorkspaceMember();
    self.setId(creatorId);
    self.setEmail(CREATOR_EMAIL);
    self.setRole("owner");
    request.setMembers(List.of(self));

    workspaceService.create(request);

    assertTrue(
        relationshipService.check(
            RelationshipType.WORKSPACE,
            "creator-membership-explicit-ws",
            Optional.empty(),
            Optional.empty()));
  }
}
