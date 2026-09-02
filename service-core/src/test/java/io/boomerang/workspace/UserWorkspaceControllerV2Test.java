package io.boomerang.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.UserService;
import io.boomerang.core.model.User;
import io.boomerang.workspace.model.WorkspaceSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code GET /api/v2/user/&#123;userId&#125;/workspaces} backs the admin User detail Workspaces
 * tab. The rollup must be resolved for the VIEWED user's id - the same
 * getTeamRefsAndRolesForUser/getWorkspaceMembershipSummary composition ProfileControllerV2 performs
 * for the current user - and an unknown id must fail as USER_NOT_FOUND rather than return an empty
 * (or the caller's own) membership list.
 */
@ExtendWith(MockitoExtension.class)
class UserWorkspaceControllerV2Test {

  private static final String USER_ID = "5f170b3df6ab327e302cb0a5";

  @Mock private UserService userService;
  @Mock private WorkspaceService workspaceService;

  private UserWorkspaceControllerV2 controller;

  @BeforeEach
  void setUp() {
    controller = new UserWorkspaceControllerV2();
    ReflectionTestUtils.setField(controller, "userService", userService);
    ReflectionTestUtils.setField(controller, "workspaceService", workspaceService);
  }

  @Test
  void resolvesMembershipForTheViewedUser() {
    WorkspaceSummary summary = new WorkspaceSummary();
    summary.setName("test-workspace");
    when(userService.getUserByID(USER_ID)).thenReturn(Optional.of(new User()));
    when(userService.getTeamRefsAndRolesForUser(USER_ID)).thenReturn(Map.of("ref", "owner"));
    when(workspaceService.getWorkspaceMembershipSummary(Map.of("ref", "owner")))
        .thenReturn(List.of(summary));

    List<WorkspaceSummary> workspaces = controller.getUserWorkspaces(USER_ID);

    assertThat(workspaces).hasSize(1);
    assertThat(workspaces.get(0).getName()).isEqualTo("test-workspace");
  }

  @Test
  void unknownUserFailsInsteadOfReturningEmptyMembership() {
    when(userService.getUserByID(USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getUserWorkspaces(USER_ID))
        .isInstanceOf(BoomerangException.class)
        .extracting("reason")
        .isEqualTo("USER_NOT_FOUND");
  }
}
