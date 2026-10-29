package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
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
 * A template is run in a workspace by name: the first submit adopts the workspace's link to it
 * under the template's name and later submits reuse that link; and a runworkflow task of a template
 * that names another template runs that template as the run's workspace's own link, adopted the
 * same way by the engine, so a child never runs in the system workspace on a workspace's behalf.
 */
class WorkflowTemplateSubmitTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowTemplateService templateService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkspaceService workspaceService;

  private String workspace;
  private String runWorkflowTaskSlug;

  @BeforeEach
  void aMemberOfAWorkspaceAndOfSystem() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("tpl-submit-" + System.nanoTime());
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    String member = "tpl-submitter-" + System.nanoTime();
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

    Task runWorkflow = new Task();
    runWorkflow.setName("tpl-run-workflow-" + System.nanoTime());
    runWorkflow.setType(TaskType.runworkflow);
    runWorkflow.getSpec().setParams(List.of(param("workflowRef"), param("wait")));
    runWorkflowTaskSlug = taskService.createGlobal(runWorkflow).getName();
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theFirstSubmitAdoptsTheLinkAndTheSecondReusesIt() {
    Workflow template = workflowService.create(SYSTEM, definition("adopted", WorkflowType.template));
    int before = workspaceWorkflows().size();

    WorkflowRun first =
        templateService.submit(workspace, template.getName(), new WorkflowSubmitRequest(), false);
    WorkflowRun second =
        templateService.submit(workspace, template.getName(), new WorkflowSubmitRequest(), false);

    assertThat(workspaceWorkflows()).hasSize(before + 1);
    WorkflowEntity link = linkTo(template.getName());
    assertThat(link.getTemplateRef().getRef()).isEqualTo(systemWorkflowId(template.getName()));
    for (WorkflowRun run : List.of(first, second)) {
      WorkflowRunEntity stored = workflowRunRepository.findById(run.getId()).orElseThrow();
      assertThat(stored.getWorkflowRef()).as("the run is the link's").isEqualTo(link.getId());
      assertThat(workflowRevisionRepository.findById(stored.getWorkflowRevisionRef()))
          .map(r -> r.getWorkflowRef())
          .as("the revision it runs is the template's")
          .contains(systemWorkflowId(template.getName()));
    }
  }

  @Test
  void aStarterOrAnUnknownNameIsRefused() {
    Workflow starter = workflowService.create(SYSTEM, definition("starter", WorkflowType.starter));
    for (String name : List.of(starter.getName(), "no-such-template-" + System.nanoTime())) {
      assertThatThrownBy(
              () -> templateService.submit(workspace, name, new WorkflowSubmitRequest(), false))
          .isInstanceOf(BoomerangException.class)
          .extracting(e -> ((BoomerangException) e).getCode())
          .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF.getCode());
    }
  }

  @Test
  void aChildTemplateRunsAsTheWorkspacesOwnLink() {
    Workflow leaf = workflowService.create(SYSTEM, definition("leaf-tpl", WorkflowType.template));
    String leafId = systemWorkflowId(leaf.getName());
    Workflow parent = definition("parent-tpl", WorkflowType.template);
    // Authored as the picker does: the child by name, stored as its id.
    parent.getTasks().add(1, runWorkflowNode(leaf.getName()));
    parent.getTasks().get(2).getDependencies().get(0).setTaskRef("child");
    parent = workflowService.create(SYSTEM, parent);

    String parentRunId =
        templateService.submit(workspace, parent.getName(), new WorkflowSubmitRequest(), true).getId();

    awaitEngine("the runworkflow task of the template's run succeeded")
        .untilAsserted(
            () -> {
              TaskRunEntity task = runWorkflowTask(parentRunId);
              assertThat(task).isNotNull();
              assertThat(task.getStatus()).isEqualTo(RunStatus.succeeded);
              assertThat(task.getPhase()).isEqualTo(RunPhase.completed);
            });

    String childRunId = resultValue(runWorkflowTask(parentRunId), "workflowRunRef");
    WorkflowRunEntity child = workflowRunRepository.findById(childRunId).orElseThrow();
    WorkflowEntity leafLink = linkTo(leaf.getName());
    assertThat(child.getWorkflowRef())
        .as("the child is the workspace's link to the leaf template, adopted by the engine")
        .isEqualTo(leafLink.getId());
    assertThat(workflowRevisionRepository.findById(child.getWorkflowRevisionRef()))
        .map(r -> r.getWorkflowRef())
        .contains(leafId);
    assertThat(workflowService.resolveChildWorkflowRef(leafId, leafLink.getId()))
        .as("the next child reuses the link")
        .isEqualTo(leafLink.getId());
  }

  @Test
  void aChildOfASystemRunOrOfAPlainWorkflowIsUnchanged() {
    Workflow leaf = workflowService.create(SYSTEM, definition("system-leaf", WorkflowType.template));
    Workflow plain = workflowService.create(workspace, definition("plain", WorkflowType.workflow));
    String leafId = systemWorkflowId(leaf.getName());
    String plainId = workspaceWorkflowId(plain.getName());

    assertThat(workflowService.resolveChildWorkflowRef(leafId, leafId))
        .as("a template run in the system workspace runs its children there")
        .isEqualTo(leafId);
    assertThat(workflowService.resolveChildWorkflowRef(plainId, plainId))
        .as("a plain workflow is never re-pointed")
        .isEqualTo(plainId);
  }

  private WorkflowTask runWorkflowNode(String childWorkflowName) {
    WorkflowTask task = new WorkflowTask();
    task.setName("child");
    task.setType(TaskType.runworkflow);
    task.setTaskRef(runWorkflowTaskSlug);
    task.setParams(
        new LinkedList<>(
            List.of(new RunParam("workflowRef", childWorkflowName), new RunParam("wait", "false"))));
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef("start");
    task.setDependencies(new LinkedList<>(List.of(dependency)));
    return task;
  }

  private static Workflow definition(String name, WorkflowType type) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setType(type);
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

  private static AbstractParam param(String name) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("text");
    return param;
  }

  private TaskRunEntity runWorkflowTask(String workflowRunId) {
    return taskRunRepository.findByWorkflowRunRef(workflowRunId).stream()
        .filter(t -> TaskType.runworkflow.equals(t.getType()))
        .findFirst()
        .orElse(null);
  }

  private static String resultValue(TaskRunEntity taskRun, String name) {
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .map(String::valueOf)
        .findFirst()
        .orElse(null);
  }

  private List<String> workspaceWorkflows() {
    return relationshipService.refsInWorkspace(
        RelationshipType.WORKFLOW, workspace, Optional.empty());
  }

  private WorkflowEntity linkTo(String templateName) {
    return workflowRepository
        .findFirstByIdInAndTemplateRefRefAndStatus(
            workspaceWorkflows(),
            systemWorkflowId(templateName),
            WorkflowStatus.active)
        .orElseThrow();
  }

  private String systemWorkflowId(String name) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, SYSTEM, Optional.of(List.of(name)))
        .get(0);
  }

  private String workspaceWorkflowId(String name) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }
}
