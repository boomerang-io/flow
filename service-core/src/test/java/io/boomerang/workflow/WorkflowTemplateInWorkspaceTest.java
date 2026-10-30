package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A workspace sees every template through its own workflow routes before it owns anything: the
 * get and the list answer the system workspace's template under its name, typed {@code template},
 * and the first saved setting makes it the workspace's own, a link that takes the template's place
 * in the list. A workflow of the workspace with the same name hides the template.
 */
class WorkflowTemplateInWorkspaceTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  private String workspace;

  @BeforeEach
  void aMemberOfAWorkspaceAndOfSystem() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("tpl-in-ws-" + System.nanoTime());
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    String member = "tpl-reader-" + System.nanoTime();
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
  void aWorkspaceReadsAndListsATemplateItHasNotMadeItsOwn() {
    Workflow template = workflowService.create(SYSTEM, definition("seen", WorkflowType.template));

    Workflow read = workflowService.get(workspace, template.getName(), Optional.empty(), true);
    assertThat(read.getType()).isEqualTo(WorkflowType.template);
    assertThat(read.getTemplateRef()).isNull();
    assertThat(names(read.getTasks())).isEqualTo(names(template.getTasks()));

    assertThat(listed(Optional.empty())).contains(template.getName());
    assertThat(listed(Optional.of(List.of("template")))).contains(template.getName());
    assertThat(listed(Optional.of(List.of("workflow")))).doesNotContain(template.getName());
    assertThat(workflowService.changelog(workspace, template.getName()).getBody()).isNotEmpty();
  }

  @Test
  void theFirstSavedSettingMakesTheTemplateTheWorkspacesOwn() {
    Workflow template = workflowService.create(SYSTEM, definition("settled", WorkflowType.template));
    Workflow change = new Workflow();
    change.setName(template.getName());
    change.setDisplayName("Contract review");
    change.setLabels(Map.of("team", "legal"));

    Workflow own = workflowService.apply(workspace, change, false);

    assertThat(own.getType()).isEqualTo(WorkflowType.workflow);
    assertThat(own.getTemplateRef().getName()).isEqualTo(template.getName());
    assertThat(own.getDisplayName()).isEqualTo("Contract review");
    assertThat(own.getLabels()).containsEntry("team", "legal");
    assertThat(names(own.getTasks())).as("the tasks stay the template's").isEqualTo(names(template.getTasks()));

    Workflow read = workflowService.get(workspace, template.getName(), Optional.empty(), true);
    assertThat(read.getType()).isEqualTo(WorkflowType.workflow);
    assertThat(read.getTemplateRef().getName()).isEqualTo(template.getName());
    List<Workflow> rows =
        workflowService
            .query(
                workspace, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(List.of(template.getName())), Optional.empty())
            .getContent();
    assertThat(rows).as("the link takes the template's place").hasSize(1);
    assertThat(rows.get(0).getType()).isEqualTo(WorkflowType.workflow);
    assertThat(rows.get(0).getTemplateRef().getName()).isEqualTo(template.getName());
  }

  @Test
  void aWorkflowOfTheSameNameHidesTheTemplateAndAnUnknownNameIsNoWorkflow() {
    Workflow template = workflowService.create(SYSTEM, definition("hidden", WorkflowType.template));
    Workflow own = definition("own", WorkflowType.workflow);
    own.setName(template.getName());
    workflowService.create(workspace, own);

    Workflow read = workflowService.get(workspace, template.getName(), Optional.empty(), true);
    assertThat(read.getType()).isEqualTo(WorkflowType.workflow);
    assertThat(read.getTemplateRef()).isNull();
    assertThat(
            workflowService
                .query(
                    workspace, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(List.of(template.getName())),
                    Optional.empty())
                .getContent())
        .hasSize(1);

    assertThatThrownBy(
            () ->
                workflowService.get(
                    workspace, "no-such-" + System.nanoTime(), Optional.empty(), true))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_REF.getCode());
  }

  private List<String> listed(Optional<List<String>> types) {
    return workflowService
        .query(
            workspace, Optional.of(200), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), types)
        .getContent()
        .stream()
        .map(Workflow::getName)
        .toList();
  }

  private static List<String> names(List<WorkflowTask> tasks) {
    return tasks.stream().map(WorkflowTask::getName).toList();
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
}
