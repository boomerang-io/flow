package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.ConcurrencyAdmissionListener;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What admitting a run costs the database. The counts a submit and the engine's admission check
 * make are counts over index keys of the workspace's own workflows: the same commands, and no run
 * document read, for a workspace of one workflow and of two hundred. A run's completion with
 * nothing parked anywhere costs one read.
 */
class RunAdmissionDatabaseCommandsTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "run-admission-commands-test-task";
  private static final String WORKFLOW = "run-admission-commands-workflow";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private QuotaService quotaService;
  @Autowired private ConcurrencyAdmissionListener admissionListener;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, true);
  }

  @AfterEach
  void resetQuotaFeature() {
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void theAdmissionCountsCostTheSameForOneWorkflowAndForTwoHundred() {
    // Every quota limited, so every count is made.
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(50);
    quotas.setMaxQueuedRuns(50);
    quotas.setMaxWorkflowRunMonthly(1000);
    quotas.setMaxCumulativeDuration(100_000);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("run-admission-commands");
    request.setDisplayName("run-admission-commands");
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    workflowService.create(workspace, runnableWorkflow(WORKFLOW, TASK_SLUG));
    WorkflowSubmitRequest submit = new WorkflowSubmitRequest();
    submit.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, WORKFLOW, submit, false);
    String workflowRef = run.getWorkflowRef();

    DatabaseCommands.Recorded<QuotaService.RunSlots> slotsOfOne =
        DatabaseCommands.record(() -> quotaService.runSlots(workflowRef));
    DatabaseCommands.Recorded<Object> checkOfOne = DatabaseCommands.record(() -> check(workspace));

    // 199 more workflows of the workspace, each with a run in flight.
    WorkspaceEntity entity = workspaceRepository.findByNameIgnoreCase(workspace).orElseThrow();
    for (int i = 0; i < 199; i++) {
      String ref = "run-admission-commands-ref-" + i;
      relationshipService.createNodeAndEdge(
          RelationshipType.WORKSPACE,
          entity.getId(),
          RelationshipLabel.HAS_WORKFLOW,
          RelationshipType.WORKFLOW,
          ref,
          ref,
          Optional.empty(),
          Optional.empty());
      if (i < 20) {
        savedWorkflowRun(ref, RunStatus.running, RunPhase.running);
      }
    }

    DatabaseCommands.Recorded<QuotaService.RunSlots> slotsOfMany =
        DatabaseCommands.record(() -> quotaService.runSlots(workflowRef));
    DatabaseCommands.Recorded<Object> checkOfMany = DatabaseCommands.record(() -> check(workspace));

    assertThat(slotsOfOne.result().workflowRefs()).hasSize(1);
    assertThat(slotsOfOne.result().inFlight()).isEqualTo(1);
    assertThat(slotsOfMany.result().workflowRefs()).hasSize(200);
    assertThat(slotsOfMany.result().inFlight()).isEqualTo(21);
    // The same commands for 200 workflows as for one, but for the second batch of the read of
    // the workspace's workflow edges: a first batch holds 101.
    assertThat(withoutEdgeBatches(slotsOfMany.names())).isEqualTo(slotsOfOne.names());
    assertThat(withoutEdgeBatches(checkOfMany.names())).isEqualTo(checkOfOne.names());
    assertThat(slotsOfMany.names()).hasSize(slotsOfOne.names().size() + 1);
    assertThat(checkOfMany.names()).hasSize(checkOfOne.names().size() + 1);
    // Every read of the runs is a count that returns a number: no find returns a run.
    assertThat(onRuns(slotsOfMany.names())).containsOnly("aggregate flowtest_workflow_runs").hasSize(2);
    assertThat(onRuns(checkOfMany.names())).containsOnly("aggregate flowtest_workflow_runs").hasSize(5);
    assertThat(slotsOfMany.documentsRead("workflow_runs")).isZero();
    assertThat(checkOfMany.documentsRead("workflow_runs")).isZero();
    // The quotas settings are read once for every limit, and both monthly figures, runs and
    // minutes, come from one audit aggregation.
    assertThat(on(checkOfMany.names(), "settings")).containsExactly("find flowtest_settings", "find flowtest_settings");
    assertThat(on(checkOfMany.names(), "audit")).containsExactly("aggregate flowtest_audit");

    // A workspace under its limits costs nothing more with workflows listed as unmetered: the
    // live count is recounted without them only when it would refuse.
    String previous = setSettingValue("quotas", "unmetered.workflows", "some-system-workflow");
    try {
      DatabaseCommands.Recorded<Object> checkWithList = DatabaseCommands.record(() -> check(workspace));
      assertThat(checkWithList.names()).isEqualTo(checkOfMany.names());
    } finally {
      setSettingValue("quotas", "unmetered.workflows", previous);
    }

    workflowRunService.cancel(run.getId());
  }

  // The commands on one collection, by name.
  private static List<String> on(List<String> names, String collection) {
    return names.stream().filter(name -> name.endsWith("flowtest_" + collection)).toList();
  }

  private static List<String> withoutEdgeBatches(List<String> names) {
    return names.stream().filter(name -> !name.startsWith("getMore ")).toList();
  }

  private static List<String> onRuns(List<String> names) {
    return names.stream().filter(name -> name.endsWith("workflow_runs")).toList();
  }

  private Object check(String workspace) {
    quotaService.assertRunQuotas(workspace, Optional.empty(), null, null);
    return null;
  }

  @Test
  void aCompletionWithNothingParkedCostsOneRead() {
    cancelRunsEarlierTestsLeftParked();
    WorkflowRunTransition completion =
        new WorkflowRunTransition(
            "run-admission-commands-run",
            "run-admission-commands-completed-workflow",
            RunStatus.running,
            RunPhase.running,
            RunStatus.succeeded,
            RunPhase.completed);

    DatabaseCommands.Recorded<Object> recorded =
        DatabaseCommands.record(
            () -> {
              admissionListener.onWorkflowRunTransition(completion);
              return null;
            });

    assertThat(recorded.names()).containsExactly("aggregate flowtest_workflow_runs");
  }
}
