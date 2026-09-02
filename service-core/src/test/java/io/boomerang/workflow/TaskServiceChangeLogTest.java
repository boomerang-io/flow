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
import io.boomerang.core.model.Token;
import io.boomerang.core.model.User;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
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
 * to dereference {@code identityService.getCurrentIdentity().getPrincipal()} directly. The stamp
 * is also actor-kind aware: only a user/session principal is a user id, so a key or global token
 * authors as its own name (or scope) rather than an id that resolves to the wrong thing.
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
  void createWithNoCurrentIdentityLeavesAuthorUnsetNotNpe() {
    when(identityService.getCurrentIdentity()).thenReturn(null);

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = taskService.createGlobal(request);

    assertThat(created).isNotNull();
    assertThat(created.getChangelog().getAuthor()).isNull();
  }

  @Test
  void createWithUserPrincipalResolvesAuthorToUserName() {
    Token token = new Token(AuthScope.user);
    token.setPrincipal("user-1");
    when(identityService.getCurrentIdentity()).thenReturn(token);
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

  @Test
  void createWithKeyTokenAuthorsAsTokenNameNotWorkspaceId() {
    Token token = new Token(AuthScope.key);
    token.setPrincipal("workspace-1");
    token.setName("ci-pipeline-token");
    when(identityService.getCurrentIdentity()).thenReturn(token);
    // The token name is not a user id and must survive the read-side name resolution untouched.
    when(userService.getUserByID("ci-pipeline-token")).thenReturn(Optional.empty());

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = taskService.createGlobal(request);

    assertThat(created.getChangelog().getAuthor()).isEqualTo("ci-pipeline-token");
  }

  @Test
  void createWithUnnamedGlobalTokenAuthorsAsScopeLabel() {
    Token token = new Token(AuthScope.global);
    token.setPrincipal("some-service");
    when(identityService.getCurrentIdentity()).thenReturn(token);
    when(userService.getUserByID("global")).thenReturn(Optional.empty());

    Task request = new Task();
    request.setName("my-task");
    request.setChangelog(new ChangeLog());

    Task created = taskService.createGlobal(request);

    assertThat(created.getChangelog().getAuthor()).isEqualTo("global");
  }
}
