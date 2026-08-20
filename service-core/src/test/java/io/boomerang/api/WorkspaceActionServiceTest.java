package io.boomerang.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.model.Actioner;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.model.ActionRequest;
import io.boomerang.workspace.entity.ApproverGroupEntity;
import io.boomerang.workspace.repository.ApproverGroupRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * With no current user (e.g. {@code flow.security.enabled=false} - {@code
 * UserService.getCurrentUser()} resolves to {@code null}), actioning a manual/approval task must
 * not NPE on the approver identity - see {@code WorkspaceActionService#action}, which used to
 * dereference {@code userEntity.getId()} directly. Mirrors {@code RelationshipService.check()}'s
 * no-principal branch (already allowing the request through unscoped above in the same method):
 * group membership can't be denied on a principal that doesn't exist.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceActionServiceTest {

  @Mock private ActionRepository actionRepository;
  @Mock private ApproverGroupRepository approverGroupRepository;
  @Mock private TaskRunService engineTaskRunService;
  @Mock private WorkflowService workflowService;
  @Mock private RelationshipService relationshipService;
  @Mock private UserService userService;
  @Mock private MongoTemplate mongoTemplate;

  private WorkspaceActionService workspaceActionService;

  @BeforeEach
  void setUp() {
    workspaceActionService =
        new WorkspaceActionService(
            actionRepository,
            approverGroupRepository,
            engineTaskRunService,
            workflowService,
            relationshipService,
            userService,
            mongoTemplate);
    when(relationshipService.check(any(), anyString(), any(), any())).thenReturn(true);
  }

  private ActionEntity manualAction() {
    ActionEntity entity = new ActionEntity();
    entity.setId("a1");
    entity.setWorkflowRef("w1");
    entity.setType(ActionType.manual);
    entity.setActioners(new LinkedList<>());
    entity.setNumberOfApprovers(2);
    return entity;
  }

  @Test
  void manualActionWithNoCurrentUserRecordsActionerWithNullApproverIdNotNpe() {
    when(userService.getCurrentUser()).thenReturn(null);
    ActionEntity entity = manualAction();
    when(actionRepository.findById("a1")).thenReturn(Optional.of(entity));

    ActionRequest request = new ActionRequest();
    request.setId("a1");
    request.setApproved(true);

    workspaceActionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).hasSize(1);
    Actioner actioner = entity.getActioners().get(0);
    assertThat(actioner.getApproverId()).isNull();
    assertThat(actioner.isApproved()).isTrue();
  }

  @Test
  void approvalWithGroupAndNoCurrentUserIsPermissiveNotNpe() {
    when(userService.getCurrentUser()).thenReturn(null);
    ActionEntity entity = manualAction();
    entity.setType(ActionType.approval);
    entity.setApproverGroupRef("g1");
    when(actionRepository.findById("a1")).thenReturn(Optional.of(entity));
    when(relationshipService.filter(any(), any(), any(), any())).thenReturn(List.of("g1"));
    ApproverGroupEntity group = new ApproverGroupEntity();
    group.setId("g1");
    group.setApprovers(List.of("some-other-user"));
    when(approverGroupRepository.findById("g1")).thenReturn(Optional.of(group));

    ActionRequest request = new ActionRequest();
    request.setId("a1");
    request.setApproved(true);

    workspaceActionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).hasSize(1);
    assertThat(entity.getActioners().get(0).getApproverId()).isNull();
  }

  @Test
  void approvalWithGroupAndResolvedNonMemberUserIsDenied() {
    UserEntity user = new UserEntity();
    user.setId("user-1");
    when(userService.getCurrentUser()).thenReturn(user);
    ActionEntity entity = manualAction();
    entity.setType(ActionType.approval);
    entity.setApproverGroupRef("g1");
    when(actionRepository.findById("a1")).thenReturn(Optional.of(entity));
    when(relationshipService.filter(any(), any(), any(), any())).thenReturn(List.of("g1"));
    ApproverGroupEntity group = new ApproverGroupEntity();
    group.setId("g1");
    group.setApprovers(List.of("some-other-user"));
    when(approverGroupRepository.findById("g1")).thenReturn(Optional.of(group));

    ActionRequest request = new ActionRequest();
    request.setId("a1");
    request.setApproved(true);

    workspaceActionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).isEmpty();
  }
}
