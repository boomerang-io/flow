package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.RoleEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RoleRepository;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.model.Action;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace guard on {@code GET /api/v2/workspace/&#123;workspace&#125;/action/&#123;id&#125;}.
 *
 * <p>{@link ActionService#action} already refuses an Action whose Workflow the caller cannot reach,
 * and {@code query}/{@code summary} narrow through {@code RelationshipService.filter} - but {@code
 * get} read straight from the repository, so any authenticated caller could read any workspace's
 * approval (its comments, actioners and instructions) by id. The guard is the same {@code
 * check(WORKFLOW, workflowRef)} the mutation path uses, refusing with the same {@code
 * ACTION_INVALID_REF} it answers for a missing id, so the response does not disclose whether the
 * Action exists.
 *
 * <p>Identities are session tokens with permissions resolved by {@code
 * TokenService.resolvePermissionsForUser} against a real MEMBER_OF edge and the seeded {@code
 * workspace/owner} role - the production resolution, not synthetic grants.
 */
class ActionWorkspaceAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String OWNING_WORKSPACE = "action-authz-owning-ws";
  private static final String OTHER_WORKSPACE = "action-authz-other-ws";
  private static final String OWNER_EMAIL = "action-authz-owner@test.local";
  private static final String OUTSIDER_EMAIL = "action-authz-outsider@test.local";

  @Autowired private ActionService actionService;
  @Autowired private ActionRepository actionRepository;
  @Autowired private WorkflowService workflowService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TokenService tokenService;

  private String memberId;
  private String outsiderId;
  private String actionId;

  /** Seeds under the base class's global identity; each test installs its own member identity. */
  @BeforeEach
  void seedActionInOwningWorkspace() {
    seedRelationshipRoot();
    seedOwnerRole();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OWNING_WORKSPACE, OWNING_WORKSPACE, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OTHER_WORKSPACE, OTHER_WORKSPACE, Optional.empty());
    memberId = memberOf(OWNER_EMAIL, OWNING_WORKSPACE);
    outsiderId = memberOf(OUTSIDER_EMAIL, OTHER_WORKSPACE);

    // Unique per test: @BeforeEach runs per test method against one shared database, and the
    // unscoped create paths do not enforce name uniqueness.
    String workflowId =
        createLinearWorkflow("action-authz-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        OWNING_WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowId,
        workflowId,
        Optional.empty(),
        Optional.empty());

    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowId);
    action.setType(ActionType.manual);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(new Date());
    actionId = actionRepository.save(action).getId();
  }

  @Test
  void aMemberOfAnotherWorkspaceCannotReadTheActionById() {
    installSessionIdentity(outsiderId);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> actionService.get(OTHER_WORKSPACE, actionId),
            "an Action must not be readable by a caller who cannot reach its Workflow");
    assertEquals(
        "ACTION_INVALID_REF",
        ex.getReason(),
        "the refusal must be the same error as not-found, disclosing nothing");
  }

  @Test
  void aMemberOfTheOwningWorkspaceStillReadsTheAction() {
    installSessionIdentity(memberId);

    Action action = actionService.get(OWNING_WORKSPACE, actionId);
    assertEquals(actionId, action.getId(), "the owning workspace's member must still be served");
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

  // A real user with a MEMBER_OF edge carrying the owner role - what WorkspaceService.create
  // records for a member. Idempotent: @BeforeEach runs per test against one shared database.
  private String memberOf(String email, String workspace) {
    UserEntity user = userRepository.findByEmailAndStatus(email, UserStatus.active);
    if (user == null) {
      user = new UserEntity();
      user.setEmail(email);
      user.setName(email);
      user.setType(UserType.user);
      user.setStatus(UserStatus.active);
      user = userRepository.save(user);
      relationshipService.createNode(
          RelationshipType.USER, user.getId(), email, Optional.empty());
      relationshipService.createEdge(
          RelationshipType.USER,
          user.getId(),
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          workspace,
          Optional.of(Map.of("role", "owner")));
    }
    return user.getId();
  }

  /** Mirror of the loader's roles.json workspace/owner document - permission resolution needs it. */
  private void seedOwnerRole() {
    if (roleRepository.findByTypeAndName("workspace", "owner") == null) {
      RoleEntity owner = new RoleEntity();
      owner.setType(PermissionScope.workspace);
      owner.setName("owner");
      owner.setPermissions(List.of("**/**"));
      roleRepository.save(owner);
    }
  }

  private String createLinearWorkflow(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("a", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "a")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
