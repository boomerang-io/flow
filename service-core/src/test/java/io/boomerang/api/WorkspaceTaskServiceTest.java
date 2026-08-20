package io.boomerang.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.Task;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.model.User;
import io.boomerang.core.security.IdentityService;
import io.boomerang.workflow.TaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * With no current principal (e.g. {@code flow.security.enabled=false}), creating a Task Template
 * must not NPE on the changelog author stamp - see {@code WorkspaceTaskService#updateChangeLog},
 * which used to dereference {@code identityService.getCurrentIdentity().getPrincipal()} directly.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceTaskServiceTest {

  @Mock private TaskService taskService;
  @Mock private RelationshipService relationshipService;
  @Mock private IdentityService identityService;
  @Mock private UserService userService;

  private WorkspaceTaskService workspaceTaskService;

  @BeforeEach
  void setUp() {
    workspaceTaskService =
        new WorkspaceTaskService(taskService, relationshipService, identityService, userService);
    when(relationshipService.filter(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createWithNoCurrentPrincipalLeavesAuthorUnsetNotNpe() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = workspaceTaskService.create(request);

    assertThat(created).isNotNull();
    assertThat(created.getChangelog().getAuthor()).isNull();
  }

  @Test
  void createWithCurrentPrincipalStampsAuthor() {
    when(identityService.getCurrentPrincipal()).thenReturn("user-1");
    User user = new User();
    user.setId("user-1");
    user.setName("Jane Doe");
    user.setDisplayName("");
    when(userService.getUserByID("user-1")).thenReturn(Optional.of(user));

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = workspaceTaskService.create(request);

    assertThat(created.getChangelog().getAuthor()).isEqualTo("Jane Doe");
  }
}
