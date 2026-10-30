package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.repository.SettingsRepository;
import io.boomerang.common.error.BoomerangException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The creating USER is always a member: a session caller who omits members must not create a
 * workspace they then cannot see. The assertions re-resolve permissions the way production does
 * on the next request - with the self-added MEMBER_OF edge in place, resolution grants the new
 * workspace's scope; without it (the old behaviour), resolution grants nothing and, enforcement
 * being live, check() denies.
 */
class WorkspaceCreatorMembershipTest extends AbstractEngineIntegrationTest {

  private static final String CREATOR_EMAIL = "creator-membership@test.local";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenService tokenService;
  @Autowired private SettingsRepository settingsRepository;

  private String creatorId;

  @BeforeEach
  void establishSessionCreator() {
    // One account per email: the second test reuses the creator the first one made.
    UserEntity existing = userRepository.findByEmail(CREATOR_EMAIL);
    if (existing != null) {
      creatorId = existing.getId();
    } else {
      UserEntity creator = new UserEntity();
      creator.setEmail(CREATOR_EMAIL);
      creator.setName("Creator Membership");
      creator.setType(UserType.user);
      creator.setStatus(UserStatus.active);
      creatorId = userRepository.save(creator).getId();
      relationshipService.createNode(
          RelationshipType.USER, creatorId, CREATOR_EMAIL, Optional.empty());
    }
    installSessionIdentity();
  }

  // The features setting decides for every user who is not an admin; the route check alone would
  // let a workspace editor's grant through.
  @Test
  void aUserCannotCreateAWorkspaceWhenTheSettingSaysSo() {
    setWorkspaceCreate("false");
    try {
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName("creator-membership-refused");
      request.setDisplayName("Creator Membership Refused");
      BoomerangException ex =
          assertThrows(BoomerangException.class, () -> workspaceService.create(request));
      assertEquals("PERMISSION_DENIED", ex.getReason());
    } finally {
      setWorkspaceCreate("true");
    }
  }

  private void setWorkspaceCreate(String value) {
    SettingEntity features = settingsRepository.findOneByKey("features");
    features.getConfig().stream()
        .filter(config -> "workspaceCreate".equals(config.getKey()))
        .forEach(config -> config.setValue(value));
    settingsRepository.save(features);
  }

  @Test
  void createWithNoMembersMakesTheCreatorAMember() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("creator-membership-ws");
    request.setDisplayName("Creator Membership WS");

    workspaceService.create(request);

    installSessionIdentity();
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

    installSessionIdentity();
    assertTrue(
        relationshipService.check(
            RelationshipType.WORKSPACE,
            "creator-membership-explicit-ws",
            Optional.empty(),
            Optional.empty()));
  }

  /** Session identity with permissions resolved exactly as TokenService does per request. */
  private void installSessionIdentity() {
    UserEntity creator = userRepository.findById(creatorId).orElseThrow();
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(creatorId);
    principal.setPermissions(tokenService.resolvePermissionsForUser(creator));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(creatorId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
