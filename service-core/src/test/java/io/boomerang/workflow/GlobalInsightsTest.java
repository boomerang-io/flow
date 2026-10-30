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
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightTaskRunPage;
import io.boomerang.common.model.WorkflowRunInsightTasks;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A platform-wide grant reads insights across workspaces: the summary and the task breakdown over
 * the union of the named workspaces' runs equal the sum of the per-workspace answers, {@code
 * workspaces} narrows and an unknown one contributes nothing, the task-run listing carries each
 * row's workspace newest first and needs a label, and a workspace-scoped grant is refused.
 */
class GlobalInsightsTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunInsightService insightService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private IdentityService identityService;

  private final String label = "source-" + System.nanoTime();

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theSummaryAndTasksAcrossTwoWorkspacesAreTheSumOfEach() {
    String first = workspaceWithRuns("gi-first", 2, 1);
    String second = workspaceWithRuns("gi-second", 1, 0);
    Date to = new Date(System.currentTimeMillis() + 60_000);
    Date from = new Date(to.getTime() - 86_400_000L);

    WorkflowRunInsightSummary across =
        insightService.summaryAcross(
            Optional.of(List.of(first, second)), from, to, Optional.empty(),
            Optional.of(List.of(label + "=yes")), Optional.empty(), Optional.empty());
    WorkflowRunInsightSummary one =
        insightService.summary(
            first, from, to, Optional.empty(), Optional.of(List.of(label + "=yes")),
            Optional.empty(), Optional.empty());
    WorkflowRunInsightSummary two =
        insightService.summary(
            second, from, to, Optional.empty(), Optional.of(List.of(label + "=yes")),
            Optional.empty(), Optional.empty());
    assertThat(across.getTotals().getRuns())
        .isEqualTo(one.getTotals().getRuns() + two.getTotals().getRuns())
        .isEqualTo(4);
    assertThat(across.getTotals().getSucceeded()).isEqualTo(3);
    assertThat(across.getTotals().getFailed()).isEqualTo(1);
    assertThat(across.getWorkflows()).hasSize(2);
    assertThat(across.getPreviousFrom()).isEqualTo(new Date(from.getTime() - 86_400_000L));

    WorkflowRunInsightTasks tasks =
        insightService.tasksAcross(
            Optional.of(List.of(first, second)), from, to, Optional.empty(),
            Optional.of(List.of(label + "=yes")), Optional.empty(), Optional.empty(),
            Optional.of("workflow"), Optional.empty(), Optional.empty(), 0);
    assertThat(tasks.getRows()).hasSize(2);
    assertThat(tasks.getRows().stream().mapToLong(WorkflowRunInsightTasks.Row::getTaskRuns).sum())
        .isEqualTo(4);

    // Narrowed to one workspace, and an unknown workspace contributes nothing.
    assertThat(
            insightService
                .summaryAcross(
                    Optional.of(List.of(second, "no-such-workspace")), from, to, Optional.empty(),
                    Optional.of(List.of(label + "=yes")), Optional.empty(), Optional.empty())
                .getTotals()
                .getRuns())
        .isEqualTo(1);
  }

  @Test
  void theTaskRunListingCarriesEachRowsWorkspaceNewestFirstAndNeedsALabel() {
    String first = workspaceWithRuns("gi-rows-a", 1, 1);
    String second = workspaceWithRuns("gi-rows-b", 1, 0);

    WorkflowRunInsightTaskRunPage page =
        insightService.taskRuns(
            Optional.of(List.of(first, second)), Optional.empty(), Optional.empty(),
            Optional.of(List.of(label + "=yes")), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(2), Optional.of(0));
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getContent()).extracting(WorkflowRunInsightTaskRunPage.TaskRunRow::getWorkspace)
        .allMatch(workspace -> workspace.equals(first) || workspace.equals(second));
    assertThat(page.getContent().get(0).getCreationDate())
        .isAfterOrEqualTo(page.getContent().get(1).getCreationDate());
    assertThat(page.getContent()).allSatisfy(row -> assertThat(row.getWorkflowName()).isNotBlank());

    assertThat(
            insightService
                .taskRuns(
                    Optional.of(List.of(first)), Optional.empty(), Optional.empty(),
                    Optional.of(List.of(label + "=yes")), Optional.of(List.of("failed")),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(WorkflowRunInsightTaskRunPage.TaskRunRow::getStatus)
        .containsExactly(RunStatus.failed);

    assertThatThrownBy(
            () ->
                insightService.taskRuns(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty()))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
  }

  @Test
  void aWorkspaceScopedGrantIsRefusedAndAGlobalOneAdmitted() {
    signIn(PermissionScope.workspace, "some-workspace");
    assertThatThrownBy(
            () ->
                identityService.requireGlobalGrant(
                    PermissionResource.INSIGHTS, PermissionAction.READ))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.PERMISSION_DENIED.getCode());

    signIn(PermissionScope.global, "**");
    identityService.requireGlobalGrant(PermissionResource.INSIGHTS, PermissionAction.READ);
  }

  // A workspace owning one workflow with the given succeeded and failed runs, each with one task
  // run of the same status, all labelled so the task-run listing has its index.
  private String workspaceWithRuns(String prefix, int succeeded, int failed) {
    String workspace = prefix + "-" + System.nanoTime();
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        workspace,
        workspace,
        Optional.empty(),
        Optional.empty());
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName(prefix + "-wf-" + System.nanoTime());
    workflow.setStatus(WorkflowStatus.active);
    workflow.setCreationDate(new Date());
    workflow = workflowRepository.save(workflow);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflow.getId(),
        workflow.getName(),
        Optional.empty(),
        Optional.empty());
    for (int i = 0; i < succeeded + failed; i++) {
      RunStatus status = (i < succeeded) ? RunStatus.succeeded : RunStatus.failed;
      WorkflowRunEntity run = savedWorkflowRun(workflow.getId(), status, RunPhase.completed);
      run.setLabels(labelled());
      run.setTrigger("manual");
      run.setDuration(1_000);
      workflowRunRepository.save(run);
      TaskRunEntity task =
          savedTaskRun(
              "work", TaskType.template, status, RunPhase.completed, workflow.getId(), run.getId());
      task.setLabels(labelled());
      task.setStartTime(new Date());
      task.setDuration(500);
      taskRunRepository.save(task);
    }
    return workspace;
  }

  private Map<String, String> labelled() {
    Map<String, String> labels = new HashMap<>();
    labels.put(label, "yes");
    return labels;
  }

  private void signIn(PermissionScope scope, String principalScope) {
    String principal = "gi-user-" + System.nanoTime();
    Token token = new Token(scope == PermissionScope.global ? AuthScope.global : AuthScope.session);
    token.setPrincipal(principal);
    token.setPermissions(
        List.of(new ResolvedPermissions(scope, principalScope, List.of("insights/read"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null);
    authentication.setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
