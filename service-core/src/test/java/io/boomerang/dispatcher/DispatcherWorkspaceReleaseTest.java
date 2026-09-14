package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkspaceReleaseQuery;
import io.boomerang.common.model.WorkspaceReleaseResponse;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The workspace release query: the dispatcher lists the owners of the volumes it still holds and
 * the engine answers which of them are finished. A WorkflowRun owner is finished once its run is
 * completed or gone; a Workflow owner once the Workflow is deleted (the tombstone) or gone. No
 * release state is recorded on either side - the same question asked twice gets the same answer.
 */
class DispatcherWorkspaceReleaseTest extends AbstractEngineIntegrationTest {

  private static final int MAX_RELEASE_REFS = 500;

  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRepository workflowRepository;

  private final List<String> createdRunIds = new ArrayList<>();
  private final List<String> createdWorkflowIds = new ArrayList<>();

  // These fixtures are unfinished runs and live workflows, which several watcher sweeps page. The
  // Testcontainers Mongo is shared across every test class, so leaving them behind would crowd
  // those fixed-size sweep pages and make another class's assertions depend on ordering.
  @AfterEach
  void removeFixtures() {
    createdRunIds.forEach(workflowRunRepository::deleteById);
    createdWorkflowIds.forEach(workflowRepository::deleteById);
    createdRunIds.clear();
    createdWorkflowIds.clear();
  }

  private String savedRun(RunStatus status, RunPhase phase) {
    String id = savedWorkflowRun("release-wf", status, phase).getId();
    createdRunIds.add(id);
    return id;
  }

  private static WorkspaceReleaseQuery runQuery(List<String> refs) {
    WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
    query.setWorkflowRunRefs(refs);
    return query;
  }

  private static WorkspaceReleaseQuery workflowQuery(List<String> refs) {
    WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
    query.setWorkflowRefs(refs);
    return query;
  }

  private String savedWorkflow(WorkflowStatus status) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName("release-" + UUID.randomUUID());
    workflow.setStatus(status);
    String id = workflowRepository.save(workflow).getId();
    createdWorkflowIds.add(id);
    return id;
  }

  @Test
  void aCompletedRunsVolumeIsReleasable() {
    String runId = savedRun(RunStatus.succeeded, RunPhase.completed);

    WorkspaceReleaseResponse response = dispatcherService.releasable(runQuery(List.of(runId)));

    assertThat(response.getWorkflowRunRefs()).containsExactly(runId);
  }

  @Test
  void aRunningRunsVolumeIsHeld() {
    String runId = savedRun(RunStatus.running, RunPhase.running);

    WorkspaceReleaseResponse response = dispatcherService.releasable(runQuery(List.of(runId)));

    assertThat(response.getWorkflowRunRefs()).isEmpty();
  }

  /**
   * A volume whose owner the engine has never heard of - or whose run has since been pruned - is
   * released rather than leaked. Absence is an answer here, which is what lets the reconciliation
   * be the only cleanup path.
   */
  @Test
  void anUnknownRunRefIsReleasable() {
    String unknown = new ObjectId().toHexString();

    WorkspaceReleaseResponse response = dispatcherService.releasable(runQuery(List.of(unknown)));

    assertThat(response.getWorkflowRunRefs()).containsExactly(unknown);
  }

  @Test
  void aDeletedWorkflowsVolumeIsReleasable() {
    String workflowRef = savedWorkflow(WorkflowStatus.deleted);

    WorkspaceReleaseResponse response =
        dispatcherService.releasable(workflowQuery(List.of(workflowRef)));

    assertThat(response.getWorkflowRefs()).containsExactly(workflowRef);
  }

  @Test
  void aLiveWorkflowsVolumeIsHeld() {
    String workflowRef = savedWorkflow(WorkflowStatus.active);

    WorkspaceReleaseResponse response =
        dispatcherService.releasable(workflowQuery(List.of(workflowRef)));

    assertThat(response.getWorkflowRefs()).isEmpty();
  }

  @Test
  void aMixedQueryAnswersEachListIndependently() {
    String completedRun = savedRun(RunStatus.succeeded, RunPhase.completed);
    String runningRun = savedRun(RunStatus.running, RunPhase.running);
    String deletedWorkflow = savedWorkflow(WorkflowStatus.deleted);
    String liveWorkflow = savedWorkflow(WorkflowStatus.active);

    WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
    query.setWorkflowRunRefs(List.of(completedRun, runningRun));
    query.setWorkflowRefs(List.of(deletedWorkflow, liveWorkflow));

    WorkspaceReleaseResponse response = dispatcherService.releasable(query);

    assertThat(response.getWorkflowRunRefs()).containsExactly(completedRun);
    assertThat(response.getWorkflowRefs()).containsExactly(deletedWorkflow);
  }

  @Test
  void aQueryOverTheCapIsRejected() {
    List<String> tooMany =
        IntStream.rangeClosed(0, MAX_RELEASE_REFS)
            .mapToObj(i -> new ObjectId().toHexString())
            .toList();
    assertThat(tooMany).hasSize(MAX_RELEASE_REFS + 1);

    assertThatThrownBy(() -> dispatcherService.releasable(runQuery(tooMany)))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getStatus())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aQueryAtTheCapIsAnswered() {
    String completedRun = savedRun(RunStatus.succeeded, RunPhase.completed);
    List<String> atCap =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(completedRun),
                IntStream.range(1, MAX_RELEASE_REFS).mapToObj(i -> new ObjectId().toHexString()))
            .toList();
    assertThat(atCap).hasSize(MAX_RELEASE_REFS);

    WorkspaceReleaseResponse response = dispatcherService.releasable(runQuery(atCap));

    assertThat(response.getWorkflowRunRefs()).hasSize(MAX_RELEASE_REFS);
  }
}
