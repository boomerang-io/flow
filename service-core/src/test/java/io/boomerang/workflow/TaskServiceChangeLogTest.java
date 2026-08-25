package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.Task;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.model.User;
import io.boomerang.core.security.IdentityService;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * With no current principal (e.g. {@code flow.security.enabled=false}), creating a Task Template
 * must not NPE on the changelog author stamp - see {@code TaskService#stampChangeLog}, which used
 * to dereference {@code identityService.getCurrentIdentity().getPrincipal()} directly.
 *
 * <p>F3 collapsed {@code api.WorkspaceTaskService} into {@link TaskService}, so this no longer
 * mocks the delegate it used to sit in front of: it drives the real merged service with the
 * repositories mocked, which is the only shape that still exercises the stamp. The two repository
 * stubs stand in for the persistence the deleted pass-through never reached.
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceChangeLogTest {

  @Mock private TaskRepository taskRepository;
  @Mock private TaskRevisionRepository taskRevisionRepository;
  @Mock private TaskRunRepository taskRunRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private RelationshipService relationshipService;
  @Mock private IdentityService identityService;
  @Mock private UserService userService;

  private TaskService taskService;

  @BeforeEach
  void setUp() {
    taskService =
        new TaskService(
            taskRepository,
            taskRevisionRepository,
            taskRunRepository,
            mongoTemplate,
            relationshipService,
            identityService,
            userService);
    // No existing global Task of this slug, so createGlobal proceeds to the create.
    when(relationshipService.filter(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(taskRepository.save(any(TaskEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(taskRevisionRepository.save(any(TaskRevisionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createWithNoCurrentPrincipalLeavesAuthorUnsetNotNpe() {
    when(identityService.getCurrentPrincipal()).thenReturn(null);

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = taskService.createGlobal(request);

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

    Task created = taskService.createGlobal(request);

    assertThat(created.getChangelog().getAuthor()).isEqualTo("Jane Doe");
  }
}
