package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.model.Actioner;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.model.Action;
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
import org.springframework.http.ResponseEntity;

/**
 * With no current USER, actioning a manual/approval task must not NPE on the approver identity -
 * see {@code ActionService#action}, which used to dereference {@code userEntity.getId()}
 * directly.
 *
 * <p>Note this is NOT the "no identity" case, which no longer exists: an identity is always
 * established. {@code UserService.getCurrentUser()} still resolves to {@code null} whenever that
 * identity is not a user - the {@code UnauthenticatedGlobalToken} when {@code
 * flow.security.enabled=false}, and equally any {@code key}/{@code global} machine token when
 * security IS enabled. So the {@code userEntity == null} branch in {@code action} remains
 * genuinely reachable and was deliberately left in place: it currently sets {@code partOfGroup =
 * true}, i.e. a machine token bypasses approver-group membership. Tightening that would change
 * authorization semantics under security-enabled and is a separate maintainer decision.
 */
@ExtendWith(MockitoExtension.class)
class ActionServiceTest {

  @Mock private ActionRepository actionRepository;
  @Mock private ApproverGroupRepository approverGroupRepository;
  @Mock private TaskRunService engineTaskRunService;
  @Mock private WorkflowService workflowService;
  @Mock private RelationshipService relationshipService;
  @Mock private UserService userService;
  @Mock private io.boomerang.core.security.IdentityService identityService;
  @Mock private MongoTemplate mongoTemplate;

  private ActionService actionService;

  @BeforeEach
  void setUp() {
    actionService =
        new ActionService(
            actionRepository,
            approverGroupRepository,
            engineTaskRunService,
            workflowService,
            relationshipService,
            userService,
            identityService,
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

    actionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).hasSize(1);
    Actioner actioner = entity.getActioners().get(0);
    assertThat(actioner.getApproverId()).isNull();
    assertThat(actioner.isApproved()).isTrue();
  }

  @Test
  void approvalWithGroupDeniesACallerWithNoUserRecord() {
    // A group approval is a membership test - a machine token (resolves no user record) cannot
    // be a member, so its decision is not recorded. Automations that must approve are given a
    // real user identity and placed in the group.
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

    actionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).isEmpty();
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

    actionService.action("team1", List.of(request));

    assertThat(entity.getActioners()).isEmpty();
  }

  // #378: the Actions table must show the Workflow's display name, resolved fresh at
  // retrieval (never stored on ActionEntity) since it can change after the Action is created.
  @Test
  void getResolvesWorkflowNameFromDisplayName() {
    ActionEntity entity = manualAction();
    when(actionRepository.findById("a1")).thenReturn(Optional.of(entity));

    Workflow workflow = new Workflow();
    workflow.setName("my-workflow-slug");
    workflow.setDisplayName("My Workflow");
    when(workflowService.get("w1", Optional.empty(), false))
        .thenReturn(ResponseEntity.ok(workflow));
    when(engineTaskRunService.get(any())).thenReturn(ResponseEntity.ok(new TaskRun()));

    Action action = actionService.get("team1", "a1");

    assertThat(action.getWorkflowName()).isEqualTo("My Workflow");
  }

  @Test
  void getFallsBackToNameWhenDisplayNameIsBlank() {
    ActionEntity entity = manualAction();
    when(actionRepository.findById("a1")).thenReturn(Optional.of(entity));

    Workflow workflow = new Workflow();
    workflow.setName("my-workflow-slug");
    workflow.setDisplayName(" ");
    when(workflowService.get("w1", Optional.empty(), false))
        .thenReturn(ResponseEntity.ok(workflow));
    when(engineTaskRunService.get(any())).thenReturn(ResponseEntity.ok(new TaskRun()));

    Action action = actionService.get("team1", "a1");

    assertThat(action.getWorkflowName()).isEqualTo("my-workflow-slug");
  }
}
