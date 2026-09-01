package io.boomerang.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.UserRequest;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The self-vs-admin split on {@code PATCH}/{@code DELETE /api/v2/user/&#123;userId&#125;}.
 *
 * <p>The route-level {@code @AuthCriteria(resource = USER)} match is scope-blind:
 * SecurityInterceptor matches {@code user/write} against EVERY grant's action strings with no
 * scope filter, and the seeded workspace {@code editor} ({@code **}/{@code write}) and {@code
 * owner} ({@code **}/{@code **}) roles both satisfy it - so before this guard, any plain member of
 * any workspace could modify (and an owner delete) ANY user's account. The service now permits
 * self-service on principal equality and otherwise requires a global-scoped {@code user/<action>}
 * grant, which {@code TokenService.resolvePermissionsForUser} only issues for the platform
 * admin/operator roles (workspace grants are workspace-scoped by construction).
 *
 * <p>Identities are session tokens with permissions resolved by {@code
 * TokenService.resolvePermissionsForUser} against real MEMBER_OF edges and the seeded roles - the
 * production resolution, not synthetic grants.
 */
class UserSelfServiceAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "user-authz-ws";
  private static final String OWNER_EMAIL = "user-authz-owner@test.local";
  private static final String TARGET_EMAIL = "user-authz-target@test.local";
  private static final String ADMIN_EMAIL = "user-authz-admin@test.local";

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TokenService tokenService;

  private String ownerId;
  private String targetId;
  private String adminId;

  @BeforeEach
  void seedUsersAndRoles() {
    seedRelationshipRoot();
    seedWorkspaceRoles();
    seedGlobalAdminRole();
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, WORKSPACE)) {
      relationshipService.createNode(
          RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, Optional.empty());
    }
    // Two plain members holding the workspace owner role (**/** - the widest workspace grant),
    // and one platform admin.
    ownerId = memberUser(OWNER_EMAIL, "owner");
    targetId = memberUser(TARGET_EMAIL, "owner");
    adminId = plainUser(ADMIN_EMAIL, UserType.admin);
  }

  @Test
  void aWorkspaceOwnerCannotModifyAnotherUsersAccount() {
    String nameBefore = userRepository.findById(targetId).orElseThrow().getName();
    installSessionIdentity(ownerId);
    UserRequest request = new UserRequest();
    request.setId(targetId);
    request.setName("hijacked");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> userService.apply(request),
            "a workspace grant must not reach another user's account");
    assertEquals("PERMISSION_DENIED", ex.getReason());
    assertEquals(
        nameBefore,
        userRepository.findById(targetId).orElseThrow().getName(),
        "the refused update must not touch the target");
  }

  @Test
  void aWorkspaceOwnerCannotDeleteAnotherUsersAccount() {
    installSessionIdentity(ownerId);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> userService.delete(targetId),
            "a workspace grant must not delete another user's account");
    assertEquals("PERMISSION_DENIED", ex.getReason());
    assertTrue(userRepository.findById(targetId).isPresent(), "the target must survive");
  }

  @Test
  void aUserCanModifyTheirOwnAccount() {
    installSessionIdentity(ownerId);
    UserRequest request = new UserRequest();
    request.setId(ownerId);
    request.setDisplayName("Self Service");

    userService.apply(request);

    assertEquals(
        "Self Service", userRepository.findById(ownerId).orElseThrow().getDisplayName());
  }

  @Test
  void aUserCanDeleteTheirOwnAccount() {
    // Membership-free: delete() refuses any user who still holds workspace roles, so the
    // self-delete case needs a user with none.
    String selfId = plainUser("user-authz-self-delete@test.local", UserType.user);
    installSessionIdentity(selfId);

    userService.delete(selfId);

    assertTrue(userRepository.findById(selfId).isEmpty(), "the self-delete must be served");
  }

  @Test
  void anAdminModifiesAndDeletesOtherUsers() {
    installSessionIdentity(adminId);

    UserRequest request = new UserRequest();
    request.setId(targetId);
    request.setName("renamed-by-admin");
    userService.apply(request);
    assertEquals("renamed-by-admin", userRepository.findById(targetId).orElseThrow().getName());

    String doomedId = plainUser("user-authz-doomed@test.local", UserType.user);
    userService.delete(doomedId);
    assertTrue(userRepository.findById(doomedId).isEmpty(), "the admin delete must be served");
  }

  /** The maintainer-flagged NPE: update-by-email of a non-existent user must be a mapped error. */
  @Test
  void updateByEmailOfANonExistentUserFailsWithUserNotFound() {
    installSessionIdentity(adminId);
    UserRequest request = new UserRequest();
    request.setEmail("user-authz-nobody@test.local");
    request.setName("nobody");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> userService.apply(request),
            "a non-existent target must be a mapped domain error, not an NPE");
    assertEquals("USER_NOT_FOUND", ex.getReason());
  }

  /** Session identity with permissions resolved exactly as TokenService does per request. */
  private void installSessionIdentity(String userId) {
    UserEntity user = userRepository.findById(userId).orElseThrow();
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userId);
    principal.setPermissions(tokenService.resolvePermissionsForUser(user));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  // Idempotent per shared database; name defaults to the email so unchanged-assertions can key
  // on it.
  private String plainUser(String email, UserType type) {
    UserEntity user = userRepository.findByEmailAndStatus(email, UserStatus.active);
    if (user == null) {
      user = new UserEntity();
      user.setEmail(email);
      user.setName(email);
      user.setType(type);
      user.setStatus(UserStatus.active);
      user = userRepository.save(user);
    }
    return user.getId();
  }

  private String memberUser(String email, String role) {
    UserEntity existing = userRepository.findByEmailAndStatus(email, UserStatus.active);
    String userId = plainUser(email, UserType.user);
    if (existing == null) {
      relationshipService.createNode(RelationshipType.USER, userId, email, Optional.empty());
      relationshipService.createEdge(
          RelationshipType.USER,
          userId,
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          WORKSPACE,
          Optional.of(Map.of("role", role)));
    }
    return userId;
  }

  /** Mirror of the loader's roles.json workspace/owner document - permission resolution needs it. */
  private void seedWorkspaceRoles() {
    if (roleRepository.findByTypeAndName("workspace", "owner") == null) {
      RoleEntity owner = new RoleEntity();
      owner.setType(PermissionScope.workspace);
      owner.setName("owner");
      owner.setPermissions(List.of("**/**"));
      roleRepository.save(owner);
    }
  }

  /** Mirror of the loader's roles.json global/admin document - admin resolution needs it. */
  private void seedGlobalAdminRole() {
    if (roleRepository.findByTypeAndName("global", "admin") == null) {
      RoleEntity admin = new RoleEntity();
      admin.setType(PermissionScope.global);
      admin.setName("admin");
      admin.setPermissions(List.of("**/**"));
      roleRepository.save(admin);
    }
  }
}
