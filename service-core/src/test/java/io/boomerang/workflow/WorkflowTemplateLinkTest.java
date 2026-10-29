package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTemplateRef;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
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
 * A template is a system-workspace workflow other workspaces run by reference: a link names it,
 * shows its tasks and params read-only under its own name, and runs the template's revision in
 * the link's workspace. A template or starter body is refused anywhere but the system workspace,
 * and a template with links cannot be deleted.
 */
class WorkflowTemplateLinkTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowTemplateService templateService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkspaceService workspaceService;

  private String workspace;

  @BeforeEach
  void aMemberOfAWorkspaceAndOfSystem() {
    // A real workspace, since a create checks its quotas; the system workspace is seeded.
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("tpl-ws-" + System.nanoTime());
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    String member = "tpl-member-" + System.nanoTime();
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
  void aTemplateOrStarterIsRefusedOutsideTheSystemWorkspace() {
    for (WorkflowType type : List.of(WorkflowType.template, WorkflowType.starter)) {
      Workflow body = definition("refused-" + type, type);
      assertThatThrownBy(() -> workflowService.create(workspace, body))
          .isInstanceOf(BoomerangException.class)
          .extracting(e -> ((BoomerangException) e).getCode())
          .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_SYSTEM_ONLY.getCode());
    }
  }

  @Test
  void aLinkShowsTheTemplateReadOnlyAndRunsItsRevisionInItsOwnWorkspace() {
    Workflow template = workflowService.create(SYSTEM, definition("shared-tpl", WorkflowType.template));
    assertThat(template.getType()).isEqualTo(WorkflowType.template);

    Workflow link = workflowService.create(workspace, link("my-link", template.getName(), null));

    assertThat(link.getTemplateRef().getName()).isEqualTo(template.getName());
    assertThat(link.getType()).isEqualTo(WorkflowType.workflow);
    assertThat(names(link.getTasks())).isEqualTo(names(template.getTasks()));
    assertThat(link.getParams()).extracting(AbstractParam::getName).containsExactly("greeting");
    Workflow read = workflowService.get(workspace, "my-link", Optional.empty(), true);
    assertThat(names(read.getTasks())).isEqualTo(names(template.getTasks()));

    WorkflowRun run =
        workflowService.submit(workspace, "my-link", new WorkflowSubmitRequest(), false);
    WorkflowRunEntity stored = workflowRunRepository.findById(run.getId()).orElseThrow();
    String templateId = systemWorkflowId(template.getName());
    String linkId = workspaceWorkflowId("my-link");
    assertThat(stored.getWorkflowRef()).as("the run is the link's, in its workspace").isEqualTo(linkId);
    assertThat(workflowRevisionRepository.findById(stored.getWorkflowRevisionRef()))
        .map(r -> r.getWorkflowRef())
        .as("the revision it runs is the template's")
        .contains(templateId);
    assertThat(templateService.query(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(List.of(template.getName())), Optional.of(List.of("template"))).getContent())
        .extracting(Workflow::getName)
        .containsExactly(template.getName());
  }

  @Test
  void aPinnedLinkKeepsItsVersionWhileAnUnpinnedOneFollowsTheLatest() {
    Workflow template = workflowService.create(SYSTEM, definition("pinned-tpl", WorkflowType.template));
    Workflow pinned = workflowService.create(workspace, link("pinned", template.getName(), 1));
    Workflow latest = workflowService.create(workspace, link("latest", template.getName(), null));

    Workflow second = definition("pinned-tpl", WorkflowType.template);
    second.setName(template.getName());
    second.getParams().add(param("second"));
    workflowService.apply(SYSTEM, second, false);

    assertThat(workflowService.get(workspace, pinned.getName(), Optional.empty(), true).getParams())
        .extracting(AbstractParam::getName)
        .containsExactly("greeting");
    assertThat(workflowService.get(workspace, latest.getName(), Optional.empty(), true).getParams())
        .extracting(AbstractParam::getName)
        .containsExactly("greeting", "second");
  }

  @Test
  void aTemplateWithLinksIsNotDeleted() {
    Workflow template = workflowService.create(SYSTEM, definition("held-tpl", WorkflowType.template));
    workflowService.create(workspace, link("holder", template.getName(), null));

    assertThatThrownBy(() -> workflowService.delete(SYSTEM, template.getName()))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_IN_USE.getCode());

    workflowService.delete(workspace, "holder");
    workflowService.delete(SYSTEM, template.getName());
  }

  @Test
  void aLinkToNothingIsRefused() {
    assertThatThrownBy(() -> workflowService.create(workspace, link("dangling", "no-such-template", null)))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF.getCode());
  }

  private static Workflow definition(String name, WorkflowType type) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setType(type);
    workflow.setParams(new LinkedList<>(List.of(param("greeting"))));
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    workflow.setTasks(new LinkedList<>(List.of(start, end)));
    return workflow;
  }

  private static Workflow link(String name, String templateName, Integer version) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    WorkflowTemplateRef ref = new WorkflowTemplateRef();
    ref.setName(templateName);
    ref.setVersion(version);
    workflow.setTemplateRef(ref);
    return workflow;
  }

  private static AbstractParam param(String name) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("text");
    return param;
  }

  private static List<String> names(List<WorkflowTask> tasks) {
    return tasks.stream().map(WorkflowTask::getName).toList();
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
