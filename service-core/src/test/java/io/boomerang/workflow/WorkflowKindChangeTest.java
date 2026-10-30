package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowPatch;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTemplateRef;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The system workspace changes what a workflow is to other workspaces with a patch of its type:
 * a workflow becomes a template or a starter and back, its revision untouched. Any other workspace
 * is refused a shared kind, a link can never be one, and a template that workspaces still link
 * stays a template.
 */
class WorkflowKindChangeTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  private String workspace;

  @BeforeEach
  void aMemberOfAWorkspaceAndOfSystem() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("kind-ws-" + System.nanoTime());
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    String member = "kind-member-" + System.nanoTime();
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, SYSTEM)) {
      relationshipService.createNode(RelationshipType.WORKSPACE, SYSTEM, SYSTEM, Optional.empty());
    }
    relationshipService.createNode(RelationshipType.USER, member, member, Optional.empty());
    for (String ws : List.of(workspace, SYSTEM)) {
      relationshipService.createEdge(
          RelationshipType.USER,
          member,
          RelationshipLabel.MEMBER_OF,
          RelationshipType.WORKSPACE,
          ws,
          Optional.empty());
    }
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(member);
    principal.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.workspace, workspace, List.of("**/**")),
            new ResolvedPermissions(PermissionScope.workspace, SYSTEM, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(member, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theSystemWorkspaceTurnsAWorkflowIntoATemplateOrAStarterAndBack() {
    Workflow created = workflowService.create(SYSTEM, definition("kind"));
    int version = created.getVersion();

    assertThat(workflowService.patch(SYSTEM, created.getName(), patch(WorkflowType.template)).getType())
        .isEqualTo(WorkflowType.template);
    assertThat(workflowService.patch(SYSTEM, created.getName(), patch(WorkflowType.starter)).getType())
        .isEqualTo(WorkflowType.starter);
    Workflow back = workflowService.patch(SYSTEM, created.getName(), patch(WorkflowType.workflow));
    assertThat(back.getType()).isEqualTo(WorkflowType.workflow);
    assertThat(back.getVersion()).as("the revision is untouched").isEqualTo(version);
    assertThat(workflowService.get(SYSTEM, created.getName(), Optional.empty(), true).getTasks())
        .extracting(WorkflowTask::getName)
        .containsExactly("start", "end");
  }

  @Test
  void anotherWorkspaceIsRefusedASharedKind() {
    Workflow own = workflowService.create(workspace, definition("own"));
    for (WorkflowType type : List.of(WorkflowType.template, WorkflowType.starter)) {
      assertThatThrownBy(() -> workflowService.patch(workspace, own.getName(), patch(type)))
          .isInstanceOf(BoomerangException.class)
          .extracting(e -> ((BoomerangException) e).getCode())
          .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_SYSTEM_ONLY.getCode());
    }
  }

  @Test
  void aLinkedTemplateStaysATemplateAndALinkCannotBecomeOne() {
    Workflow template = workflowService.create(SYSTEM, definition("linked"));
    workflowService.patch(SYSTEM, template.getName(), patch(WorkflowType.template));
    Workflow link = new Workflow();
    link.setName("link-" + System.nanoTime());
    WorkflowTemplateRef ref = new WorkflowTemplateRef();
    ref.setName(template.getName());
    link.setTemplateRef(ref);
    workflowService.create(workspace, link);

    assertThatThrownBy(
            () -> workflowService.patch(SYSTEM, template.getName(), patch(WorkflowType.workflow)))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_IN_USE.getCode());
    assertThatThrownBy(
            () -> workflowService.patch(SYSTEM, link.getName(), patch(WorkflowType.template)))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_REF.getCode());
  }

  private static WorkflowPatch patch(WorkflowType type) {
    WorkflowPatch patch = new WorkflowPatch();
    patch.setType(type);
    return patch;
  }

  private static Workflow definition(String name) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setParams(new LinkedList<>());
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(dependency)));
    workflow.setTasks(new LinkedList<>(List.of(start, end)));
    return workflow;
  }
}
