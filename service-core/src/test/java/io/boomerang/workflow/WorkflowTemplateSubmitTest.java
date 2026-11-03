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
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.entity.RelationshipEdgeEntity;
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
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
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
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkspaceService workspaceService;

  @Autowired private MongoTemplate mongoTemplate;

  private String workspace;
  private String workspaceId;
  private String runWorkflowTaskSlug;

  @BeforeEach
  void aMemberOfAWorkspaceAndOfSystem() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("tpl-submit-" + System.nanoTime());
    request.setDisplayName(request.getName());
    Workspace created = workspaceService.create(request);
    workspace = created.getName();
    workspaceId = created.getId();
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
        workflowService.submit(workspace, template.getName(), new WorkflowSubmitRequest(), false);
    WorkflowRun second =
        workflowService.submit(workspace, template.getName(), new WorkflowSubmitRequest(), false);

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
  void aTemplateRunsTaskRunsAreTheLinksAndTheWorkspaceQueryFindsThem() {
    Workflow template = workflowService.create(SYSTEM, definition("task-runs", WorkflowType.template));
    String runId =
        workflowService.submit(workspace, template.getName(), new WorkflowSubmitRequest(), true).getId();
    awaitEngine("the template's run completed")
        .untilAsserted(
            () ->
                assertThat(workflowRunRepository.findById(runId).orElseThrow().getPhase())
                    .isEqualTo(RunPhase.completed));

    WorkflowEntity link = linkTo(template.getName());
    String revisionRef = workflowRunRepository.findById(runId).orElseThrow().getWorkflowRevisionRef();
    assertThat(taskRunRepository.findByWorkflowRunRef(runId))
        .hasSize(2)
        .allSatisfy(
            taskRun -> {
              assertThat(taskRun.getWorkflowRef()).as("the run's workflow, the link").isEqualTo(link.getId());
              assertThat(taskRun.getWorkflowRevisionRef()).as("what ran, the template's").isEqualTo(revisionRef);
            });
    assertThat(
            workflowRunService
                .queryTaskRuns(
                    workspace,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of(runId)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(TaskRun::getName)
        .containsExactlyInAnyOrder("start", "end");
  }

  @Test
  void aStarterIsNotRunAndAnUnknownNameIsNoWorkflow() {
    Workflow starter = workflowService.create(SYSTEM, definition("starter", WorkflowType.starter));
    assertThatThrownBy(
            () ->
                workflowService.submit(
                    workspace, starter.getName(), new WorkflowSubmitRequest(), false))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF.getCode());
    assertThatThrownBy(
            () ->
                workflowService.submit(
                    workspace,
                    "no-such-template-" + System.nanoTime(),
                    new WorkflowSubmitRequest(),
                    false))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_REF.getCode());
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
        workflowService.submit(workspace, parent.getName(), new WorkflowSubmitRequest(), true).getId();

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

  // Six children of a for-each runworkflow adopt the same template at once: one link, which all
  // of them run as.
  @Test
  void concurrentFirstUsesOfATemplateMakeOneLink() throws Exception {
    Workflow leaf = workflowService.create(SYSTEM, definition("raced-leaf", WorkflowType.template));
    String leafId = systemWorkflowId(leaf.getName());
    Workflow parent = workflowService.create(workspace, definition("racing-parent", WorkflowType.workflow));
    String parentId = workspaceWorkflowId(parent.getName());

    int callers = 6;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> resolved = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      resolved.add(
          pool.submit(
              () -> {
                go.await();
                return workflowService.resolveChildWorkflowRef(leafId, parentId);
              }));
    }
    go.countDown();
    Set<String> links = new HashSet<>();
    for (Future<String> future : resolved) {
      links.add(future.get(60, TimeUnit.SECONDS));
    }
    pool.shutdown();

    assertThat(links).as("every caller runs as the same link").hasSize(1);
    assertThat(linksTo(leafId)).as("the workspace holds one link to the template").hasSize(1);
    assertThat(links).containsExactly(linkTo(leaf.getName()).getId());
    assertThat(claim(leafId))
        .as("the claim runs to the link")
        .hasValueSatisfying(c -> assertThat(c.getTo()).isEqualTo("workflow:" + links.iterator().next()));
  }

  // Deleting the link removes its node and so its claim: the next first use adopts a new link.
  @Test
  void aDeletedLinkIsAdoptedAfresh() {
    Workflow leaf = workflowService.create(SYSTEM, definition("readopted", WorkflowType.template));
    String leafId = systemWorkflowId(leaf.getName());
    Workflow parent = workflowService.create(workspace, definition("readopting", WorkflowType.workflow));
    String parentId = workspaceWorkflowId(parent.getName());
    String first = workflowService.resolveChildWorkflowRef(leafId, parentId);

    workflowService.delete(workspace, leaf.getName());
    assertThat(claim(leafId)).as("the claim went with the link's node").isEmpty();

    String second = workflowService.resolveChildWorkflowRef(leafId, parentId);
    assertThat(second).isNotEqualTo(first);
    assertThat(linksTo(leafId)).containsExactly(second);
  }

  // A claim whose link never appeared, its creator having died, is taken over once stale.
  @Test
  void aStaleClaimWhoseLinkNeverAppearedIsTakenOver() {
    Workflow leaf = workflowService.create(SYSTEM, definition("orphaned-claim", WorkflowType.template));
    String leafId = systemWorkflowId(leaf.getName());
    Workflow parent = workflowService.create(workspace, definition("orphan-parent", WorkflowType.workflow));
    String parentId = workspaceWorkflowId(parent.getName());
    String deadLink = new ObjectId().toHexString();
    mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(RelationshipEdgeEntity.class))
        .insertOne(
            new Document("_id", "linksTemplate:workspace:" + workspaceId + ":" + leafId)
                .append("creationDate", new Date(System.currentTimeMillis() - 120_000))
                .append("from", "workspace:" + workspaceId)
                .append("label", "linksTemplate")
                .append("to", "workflow:" + deadLink)
                .append("data", new Document("templateRef", leafId)));

    String link = workflowService.resolveChildWorkflowRef(leafId, parentId);

    assertThat(link).isNotEqualTo(deadLink);
    assertThat(linksTo(leafId)).containsExactly(link);
    assertThat(claim(leafId)).hasValueSatisfying(c -> assertThat(c.getTo()).isEqualTo("workflow:" + link));
  }

  // A workflow of the workspace's own holds the template's name: the first use is refused and
  // gives its claim up, so it never blocks a later one.
  @Test
  void aNameTheWorkspaceAlreadyUsesRefusesTheLinkAndLeavesNoClaim() {
    Workflow leaf = workflowService.create(SYSTEM, definition("name-taken", WorkflowType.template));
    String leafId = systemWorkflowId(leaf.getName());
    Workflow own = definition("ignored", WorkflowType.workflow);
    own.setName(leaf.getName());
    workflowService.create(workspace, own);
    Workflow parent = workflowService.create(workspace, definition("name-parent", WorkflowType.workflow));
    String parentId = workspaceWorkflowId(parent.getName());

    assertThatThrownBy(() -> workflowService.resolveChildWorkflowRef(leafId, parentId))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getReason())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_REQ.getReason());
    assertThat(claim(leafId)).isEmpty();
  }

  private List<String> linksTo(String templateId) {
    return workflowRepository.findAllById(workspaceWorkflows()).stream()
        .filter(w -> w.getTemplateRef() != null && templateId.equals(w.getTemplateRef().getRef()))
        .filter(w -> WorkflowStatus.active.equals(w.getStatus()))
        .map(WorkflowEntity::getId)
        .toList();
  }

  private Optional<RelationshipEdgeEntity> claim(String templateId) {
    return relationshipService.findClaim(
        RelationshipType.WORKSPACE, workspace, RelationshipLabel.LINKS_TEMPLATE, templateId);
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
