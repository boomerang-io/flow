package io.boomerang.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.boomerang.api.model.UserProfile;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code GET /api/v2/profile} backs the webapp's bootstrap call on every route. With no
 * AuthenticationFilter running (security disabled), {@code UserService.getCurrentProfileEntity()}
 * now returns an anonymous/default {@link UserEntity} (id/email unset) rather than NPE-ing (see
 * {@code UserServiceTest} for that behaviour directly) - this proves the controller's composition
 * of that default entity with the Workspace/permission rollup still renders a response instead of
 * NPE-ing downstream (e.g. on {@code profile.getId()}).
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerV2Test {

  @Mock private UserService userService;
  @Mock private WorkspaceService workspaceService;
  @Mock private TokenService tokenService;

  private ProfileControllerV2 controller;

  @BeforeEach
  void setUp() {
    controller = new ProfileControllerV2();
    ReflectionTestUtils.setField(controller, "userService", userService);
    ReflectionTestUtils.setField(controller, "workspaceService", workspaceService);
    ReflectionTestUtils.setField(controller, "tokenService", tokenService);
  }

  @Test
  void getProfileWithAnonymousDefaultEntityRendersNotNpe() {
    // The default/anonymous UserEntity UserService.getCurrentProfileEntity() returns with no
    // resolvable principal - id/email unset, everything else Lombok-default.
    UserEntity anonymous = new UserEntity();
    when(userService.getCurrentProfileEntity()).thenReturn(anonymous);
    when(userService.getTeamRefsAndRolesForUser(null)).thenReturn(Map.of());
    when(workspaceService.getWorkspaceMembershipSummary(Map.of())).thenReturn(List.of());
    when(tokenService.resolvePermissionsForUser(any(UserEntity.class))).thenReturn(List.of());

    UserProfile profile = controller.getProfile();

    assertThat(profile).isNotNull();
    assertThat(profile.getId()).isNull();
    assertThat(profile.getTeams()).isEmpty();
    assertThat(profile.getPermissions()).isEmpty();
  }
}
