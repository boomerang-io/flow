package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import jakarta.servlet.Filter;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A workflow with nothing between start and end saves, so work in progress keeps, but a submit of
 * it is refused before any run exists: {@code 400 WORKFLOW_NOT_RUNNABLE} on the submit route and on
 * a webhook, and a retry of a run of such a revision is refused the same way. One task is enough to
 * run.
 */
class WorkflowNotRunnableTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "not-runnable-test-task";
  private static final String MESSAGE =
      "Nothing to run yet, mate: this workflow has no tasks between start and end. Add a task and"
          + " give it another go.";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private TokenService tokenService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String bearer;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("not-runnable-" + UUID.randomUUID());
    request.setPermissions(List.of("**/**"));
    bearer = "Bearer " + tokenService.create(request).getToken();
  }

  @Test
  void aSubmitIsRefusedWithTheCodeAndNoRunIsCreated() throws Exception {
    String workspace = workspace("not-runnable-submit-ws");
    String workflowId = createWorkflow(workspace, "not-runnable-submit", startAndEnd());

    mockMvc
        .perform(
            post("/api/v2/workspace/" + workspace + "/workflow/not-runnable-submit/submit")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1225))
        .andExpect(jsonPath("$.reason").value("WORKFLOW_NOT_RUNNABLE"))
        .andExpect(jsonPath("$.message").value(MESSAGE));

    assertThat(runs(workflowId)).isEmpty();
  }

  @Test
  void aWebhookAnswersWithTheRefusalAndCreatesNoRun() throws Exception {
    String workspace = workspace("not-runnable-webhook-ws");
    String workflowId = createWorkflow(workspace, "not-runnable-webhook", startAndEnd());

    mockMvc
        .perform(
            post("/api/v2/webhook")
                .param("workspace", workspace)
                .param("ref", "not-runnable-webhook")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"1.4.2\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1225))
        .andExpect(jsonPath("$.reason").value("WORKFLOW_NOT_RUNNABLE"));

    assertThat(runs(workflowId)).isEmpty();
  }

  // A run of such a revision can still exist, made before submit refused it: a retry of it is a
  // new run of the same revision, refused before the clone is written.
  @Test
  void aRetryOfARunOfSuchARevisionIsRefusedWithoutANewRun() {
    String workspace = workspace("not-runnable-retry-ws");
    String workflowId = createWorkflow(workspace, "not-runnable-retry", startAndEnd());
    WorkflowRunEntity earlier = savedWorkflowRun(workflowId, RunStatus.invalid, RunPhase.completed);
    earlier.setWorkflowRevisionRef(
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId).orElseThrow().getId());
    workflowRunRepository.save(earlier);

    assertThatThrownBy(() -> workflowRunService.retry(earlier.getId(), true, 1))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo(BoomerangError.WORKFLOW_NOT_RUNNABLE.getReason()));

    assertThat(runs(workflowId)).extracting(WorkflowRunEntity::getId).containsExactly(earlier.getId());
  }

  @Test
  void aWorkflowWithOneTaskStillRuns() {
    String workspace = workspace("not-runnable-one-task-ws");
    Workflow workflow = runnableWorkflow("not-runnable-one-task", TASK_SLUG);
    workflow.setTriggers(triggers());
    workflowService.create(workspace, workflow);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);

    WorkflowRun run = workflowService.submit(workspace, "not-runnable-one-task", request, true);

    assertThat(run.getStatus()).isNotEqualTo(RunStatus.invalid);
    assertThat(workflowRunRepository.findById(run.getId())).isPresent();
    workflowRunService.cancel(run.getId());
  }

  private static List<WorkflowTask> startAndEnd() {
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(afterStart)));
    return new LinkedList<>(List.of(start, end));
  }

  private static WorkflowTrigger triggers() {
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setWebhook(new Trigger(true));
    return triggers;
  }

  private String workspace(String workspace) {
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, workspace)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspace,
          workspace,
          Optional.empty(),
          Optional.empty());
    }
    return workspace;
  }

  private String createWorkflow(String workspace, String name, List<WorkflowTask> tasks) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(tasks);
    workflow.setTriggers(triggers());
    workflowService.create(workspace, workflow);
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }

  private List<WorkflowRunEntity> runs(String workflowId) {
    return workflowRunRepository.findByWorkflowRef(workflowId, Pageable.unpaged());
  }
}
