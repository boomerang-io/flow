package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.WorkflowRunPurge;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.ArtifactServiceTest;
import io.boomerang.workflow.ArtifactStore;
import io.boomerang.workflow.WorkflowRetentionService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.repository.ArtifactRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A purge request removes a run with everything it owns and its lineage, cancels one still in
 * flight, and waits for the dispatcher to release the run's terminated tasks before the documents
 * their terminate orders live in disappear. Audit records and the workflow stay.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class WorkflowRunPurgeTest extends AbstractEngineIntegrationTest {

  private static final String DISPATCHER = "purge-test-dispatcher";

  @Autowired private WorkflowWatcher watcher;
  @Autowired private WorkflowRetentionService workflowRetentionService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private ActionRepository actionRepository;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactStore store;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MongoTemplate mongo;

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aFinishedRunIsPurgedWithEverythingItOwnsAndItsLineage() {
    WorkflowEntity workflow = savedWorkflow("purge-lineage");
    WorkflowRunEntity origin = finishedRun(workflow.getId());
    String originTaskId = finishedTask(workflow, origin).getId();
    WorkflowRunEntity retry = finishedRun(workflow.getId());
    retry.setInitiatedByRef(origin.getId());
    retry.setTrigger("retry");
    workflowRunRepository.save(retry);
    WorkflowRunEntity child = finishedRun(workflow.getId());
    child.setInitiatedByRef(originTaskId);
    workflowRunRepository.save(child);
    String childTaskId = finishedTask(workflow, child).getId();
    WorkflowRunEntity grandchild = finishedRun(workflow.getId());
    grandchild.setInitiatedByRef(childTaskId);
    workflowRunRepository.save(grandchild);
    WorkflowRunEntity unrelated = finishedRun(workflow.getId());
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflow.getId());
    action.setWorkflowRunRef(origin.getId());
    action.setStatus(ActionStatus.approved);
    action = actionRepository.save(action);
    ArtifactEntity artifact = savedArtifact(workflow, origin, "report.txt", ArtifactScope.run);
    ArtifactEntity log = savedArtifact(workflow, child, childTaskId + ".log", ArtifactScope.log);
    ArtifactEntity kept = savedArtifact(workflow, origin, "state.json", ArtifactScope.workflow);
    relationshipService.createNode(
        RelationshipType.WORKFLOWRUN, origin.getId(), origin.getId(), Optional.empty());
    AuditEventEntity audit = new AuditEventEntity();
    audit.setResourceType("workflowrun");
    audit.setResourceId(origin.getId());
    audit = auditEventRepository.save(audit);

    WorkflowRetentionService.PurgeOutcome outcome = workflowRetentionService.purge(origin.getId());

    assertThat(outcome.purged())
        .containsExactlyInAnyOrder(
            origin.getId(), retry.getId(), child.getId(), grandchild.getId());
    assertThat(outcome.purged().getLast())
        .as("the run goes last, after every run that points at it")
        .isEqualTo(origin.getId());
    assertThat(outcome.scheduled()).isEmpty();
    for (String id : List.of(origin.getId(), retry.getId(), child.getId(), grandchild.getId())) {
      assertThat(workflowRunRepository.existsById(id)).isFalse();
    }
    assertThat(taskRunRepository.existsById(originTaskId)).isFalse();
    assertThat(taskRunRepository.existsById(childTaskId)).isFalse();
    assertThat(actionRepository.existsById(action.getId())).isFalse();
    assertThat(artifactRepository.existsById(artifact.getId())).isFalse();
    assertThat(store.head(artifact.getStorageKey())).isEmpty();
    assertThat(artifactRepository.existsById(log.getId())).isFalse();
    assertThat(artifactRepository.existsById(kept.getId()))
        .as("a workflow-scoped artifact is the workflow's state, not the run's")
        .isTrue();
    assertThat(
            relationshipService.doesSlugOrRefExistForType(
                RelationshipType.WORKFLOWRUN, origin.getId()))
        .isFalse();
    assertThat(auditEventRepository.existsById(audit.getId())).isTrue();
    assertThat(workflowRunRepository.existsById(unrelated.getId())).isTrue();
    assertThat(workflowRepository.existsById(workflow.getId())).isTrue();
  }

  @Test
  void aRunInFlightIsCancelledAndPurgedOnceTheDispatcherReleasesItsTasks() {
    WorkflowEntity workflow = savedWorkflow("purge-in-flight");
    WorkflowRunEntity run = runningRun(workflow.getId());
    String taskId = claimedTask(workflow, run).getId();

    WorkflowRetentionService.PurgeOutcome outcome = workflowRetentionService.purge(run.getId());

    assertThat(outcome.purged()).isEmpty();
    assertThat(outcome.scheduled()).containsExactly(run.getId());
    WorkflowRunEntity cancelled = workflowRunRepository.findById(run.getId()).orElseThrow();
    assertThat(cancelled.getStatus()).isEqualTo(RunStatus.cancelled);
    assertThat(cancelled.getPhase()).isEqualTo(RunPhase.completed);
    assertThat(cancelled.getPurgeRequestedAt()).isNotNull();
    // The cancel ends the run's tasks off the caller's thread.
    awaitEngine("the claimed task ends cancelled, its terminate order kept")
        .untilAsserted(
            () -> {
              TaskRunEntity terminated = taskRunRepository.findById(taskId).orElseThrow();
              assertThat(terminated.getPhase()).isEqualTo(RunPhase.completed);
              assertThat(terminated.getStatus()).isEqualTo(RunStatus.cancelled);
              assertThat(terminated.getClaim().getBy()).isEqualTo(DISPATCHER);
            });

    watcher.purgeRequestedRuns();
    assertThat(workflowRunRepository.existsById(run.getId()))
        .as("a run a dispatcher still holds a task of waits")
        .isTrue();

    releaseResidue(taskId);
    watcher.purgeRequestedRuns();

    assertThat(workflowRunRepository.existsById(run.getId())).isFalse();
    assertThat(taskRunRepository.existsById(taskId)).isFalse();
  }

  @Test
  void aHeldRunIsPurgedOnceTheReleaseGraceHasPassed() {
    WorkflowEntity workflow = savedWorkflow("purge-grace");
    WorkflowRunEntity run = runningRun(workflow.getId());
    String taskId = claimedTask(workflow, run).getId();
    workflowRetentionService.purge(run.getId());
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(run.getId())),
        new Update()
            .set(
                "purgeRequestedAt",
                new Date(
                    System.currentTimeMillis()
                        - WorkflowRetentionService.PURGE_RELEASE_GRACE_MILLIS
                        - TimeUnit.MINUTES.toMillis(1))),
        WorkflowRunEntity.class);

    watcher.purgeRequestedRuns();

    assertThat(workflowRunRepository.existsById(run.getId())).isFalse();
    assertThat(taskRunRepository.existsById(taskId)).isFalse();
  }

  @Test
  void aRunThatDoesNotExistIsNothingToDo() {
    assertThat(workflowRunService.purge("any-workspace", "000000000000000000000000"))
        .isEqualTo(WorkflowRunPurge.NONE);
    assertThat(workflowRetentionService.purge("000000000000000000000000").purged()).isEmpty();
  }

  @Test
  void aLabelPurgeTakesTheWorkspaceRunsMatchingEveryLabel() {
    String workspace = "purge-ws-" + System.nanoTime();
    String member = "purge-member-" + System.nanoTime();
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        workspace,
        workspace,
        Optional.empty(),
        Optional.empty());
    relationshipService.createNode(RelationshipType.USER, member, member, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        member,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        workspace,
        Optional.empty());
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(member);
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, workspace, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(member, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    WorkflowEntity workflow = savedWorkflow("purge-labels");
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflow.getId(),
        workflow.getName(),
        Optional.empty(),
        Optional.empty());
    String label = "erase-" + System.nanoTime();
    WorkflowRunEntity first = labelledRun(workflow.getId(), Map.of(label, "yes"));
    WorkflowRunEntity second = labelledRun(workflow.getId(), Map.of(label, "yes", "other", "x"));
    WorkflowRunEntity retryOfFirst = finishedRun(workflow.getId());
    retryOfFirst.setInitiatedByRef(first.getId());
    workflowRunRepository.save(retryOfFirst);
    WorkflowRunEntity other = labelledRun(workflow.getId(), Map.of(label, "no"));

    WorkflowRunPurge result =
        workflowRunService.purgeByLabels(workspace, Optional.of(List.of(label + "=yes")));

    assertThat(result).isEqualTo(new WorkflowRunPurge(3, 0, 0));
    assertThat(workflowRunRepository.existsById(first.getId())).isFalse();
    assertThat(workflowRunRepository.existsById(second.getId())).isFalse();
    assertThat(workflowRunRepository.existsById(retryOfFirst.getId()))
        .as("a run's lineage goes with it whether or not it carries the label")
        .isFalse();
    assertThat(workflowRunRepository.existsById(other.getId())).isTrue();

    assertThat(workflowRunService.purgeByLabels(workspace, Optional.of(List.of(label + "=yes"))))
        .as("a repeated call finds nothing")
        .isEqualTo(WorkflowRunPurge.NONE);
    assertThatThrownBy(() -> workflowRunService.purgeByLabels(workspace, Optional.empty()))
        .as("a purge of every run in the workspace is not a thing")
        .isInstanceOf(BoomerangException.class);
  }

  private WorkflowEntity savedWorkflow(String name) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setStatus(WorkflowStatus.active);
    return workflowRepository.save(workflow);
  }

  private WorkflowRunEntity finishedRun(String workflowRef) {
    return savedWorkflowRun(workflowRef, RunStatus.succeeded, RunPhase.completed);
  }

  private WorkflowRunEntity labelledRun(String workflowRef, Map<String, String> labels) {
    WorkflowRunEntity run = finishedRun(workflowRef);
    run.getLabels().putAll(labels);
    return workflowRunRepository.save(run);
  }

  // A running run whose revision is gone: the cancel winds down what is stored.
  private WorkflowRunEntity runningRun(String workflowRef) {
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    run.setWorkflowRevisionRef("000000000000000000000000");
    return workflowRunRepository.save(run);
  }

  private TaskRunEntity finishedTask(WorkflowEntity workflow, WorkflowRunEntity run) {
    return savedTaskRun(
        "task",
        TaskType.template,
        RunStatus.succeeded,
        RunPhase.completed,
        workflow.getId(),
        run.getId());
  }

  private TaskRunEntity claimedTask(WorkflowEntity workflow, WorkflowRunEntity run) {
    TaskRunEntity task =
        savedTaskRun(
            "task",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            workflow.getId(),
            run.getId());
    RunClaim claim = new RunClaim();
    claim.setBy(DISPATCHER);
    claim.setAt(new Date());
    claim.setLeaseExpiresAt(new Date(System.currentTimeMillis() + 60_000));
    claim.setSeq(1L);
    task.setClaim(claim);
    task.setStartTime(new Date());
    return taskRunRepository.save(task);
  }

  // What the dispatcher's terminate poll does to a cancelled task it held.
  private void releaseResidue(String taskId) {
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(taskId)),
        new Update().unset("claim.by").unset("claim.at").unset("claim.leaseExpiresAt"),
        TaskRunEntity.class);
  }

  private ArtifactEntity savedArtifact(
      WorkflowEntity workflow, WorkflowRunEntity run, String name, ArtifactScope scope) {
    String key = "runs/" + run.getId() + "/" + name;
    store.put(key, name.getBytes(StandardCharsets.UTF_8), "text/plain");
    ArtifactEntity artifact = new ArtifactEntity();
    artifact.setName(name);
    artifact.setWorkflowRef(workflow.getId());
    artifact.setWorkflowRunRef(run.getId());
    artifact.setScope(scope);
    artifact.setSize(name.length());
    artifact.setStorageKey(key);
    artifact.setStatus(ArtifactStatus.available);
    artifact.setCreationDate(run.getCreationDate());
    return artifactRepository.save(artifact);
  }
}
