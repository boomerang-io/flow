package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.ArtifactServiceTest;
import io.boomerang.workflow.ArtifactStore;
import io.boomerang.workflow.repository.ArtifactRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Finished runs past the Activity retention are purged with everything that belongs to them,
 * except where a later run still points at them. In-flight runs, young runs, workflow-scoped
 * artifacts and audit records are never touched.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class WorkflowRunRetentionTest extends AbstractEngineIntegrationTest {

  private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

  @Autowired private WorkflowWatcher watcher;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private ActionRepository actionRepository;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactStore store;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void restoreRetentionSetting() {
    setRunRetention(null);
  }

  @Test
  void aFinishedRunPastRetentionIsPurgedWithEverythingItOwns() {
    WorkflowEntity workflow = savedWorkflow("retention-wf");
    WorkflowRunEntity run = finishedRun(workflow.getId(), 400);
    String taskRunId =
        savedTaskRun(
                "old-task",
                TaskType.template,
                RunStatus.succeeded,
                RunPhase.completed,
                workflow.getId(),
                run.getId())
            .getId();
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflow.getId());
    action.setWorkflowRunRef(run.getId());
    action.setStatus(ActionStatus.approved);
    action = actionRepository.save(action);
    ArtifactEntity runArtifact = savedArtifact(workflow, run, "report.txt", ArtifactScope.run);
    ArtifactEntity log = savedArtifact(workflow, run, taskRunId + ".log", ArtifactScope.log);
    ArtifactEntity kept = savedArtifact(workflow, run, "state.json", ArtifactScope.workflow);
    relationshipService.createNode(
        RelationshipType.WORKFLOWRUN, run.getId(), run.getId(), Optional.empty());
    AuditEventEntity audit = new AuditEventEntity();
    audit.setResourceType("workflowrun");
    audit.setResourceId(run.getId());
    audit = auditEventRepository.save(audit);

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(run.getId())).isFalse();
    assertThat(taskRunRepository.existsById(taskRunId)).isFalse();
    assertThat(actionRepository.existsById(action.getId())).isFalse();
    assertThat(artifactRepository.existsById(runArtifact.getId())).isFalse();
    assertThat(store.head(runArtifact.getStorageKey())).isEmpty();
    assertThat(artifactRepository.existsById(log.getId())).isFalse();
    assertThat(store.head(log.getStorageKey())).isEmpty();
    assertThat(artifactRepository.existsById(kept.getId()))
        .as("a workflow-scoped artifact is the workflow's state, not the run's")
        .isTrue();
    assertThat(store.head(kept.getStorageKey())).isPresent();
    assertThat(
            relationshipService.doesSlugOrRefExistForType(
                RelationshipType.WORKFLOWRUN, run.getId()))
        .isFalse();
    assertThat(auditEventRepository.existsById(audit.getId()))
        .as("audit records are the record that outlives the purge")
        .isTrue();
    assertThat(workflowRepository.existsById(workflow.getId())).isTrue();
  }

  @Test
  void aRunAnotherRunPointsAtOutlivesItsRetentionUntilThatRunIsGone() {
    WorkflowEntity workflow = savedWorkflow("lineage-wf");
    WorkflowRunEntity origin = finishedRun(workflow.getId(), 400);
    WorkflowRunEntity retry = finishedRun(workflow.getId(), 380);
    retry.setInitiatedByRef(origin.getId());
    workflowRunRepository.save(retry);

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(origin.getId()))
        .as("the origin stays while the retry that reuses its task runs exists")
        .isTrue();
    assertThat(workflowRunRepository.existsById(retry.getId())).isFalse();

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(origin.getId())).isFalse();
  }

  @Test
  void anInFlightRunPastRetentionIsKept() {
    WorkflowEntity workflow = savedWorkflow("waiting-wf");
    WorkflowRunEntity waiting =
        savedWorkflowRun(workflow.getId(), RunStatus.waiting, RunPhase.running);
    waiting.setCreationDate(daysAgo(400));
    workflowRunRepository.save(waiting);

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(waiting.getId())).isTrue();
  }

  @Test
  void aFinishedRunInsideRetentionIsKept() {
    WorkflowEntity workflow = savedWorkflow("recent-wf");
    WorkflowRunEntity recent = finishedRun(workflow.getId(), 10);

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(recent.getId())).isTrue();
  }

  @Test
  void theRetentionSettingIsFlooredAtSixtyDaysAndDefaultsWhenNotANumber() {
    setRunRetention("10");
    assertThat(watcher.runRetentionDays()).isEqualTo(60);

    setRunRetention("90");
    assertThat(watcher.runRetentionDays()).isEqualTo(90);

    setRunRetention("forever");
    assertThat(watcher.runRetentionDays()).isEqualTo(365);

    setRunRetention(null);
    assertThat(watcher.runRetentionDays()).isEqualTo(365);
  }

  @Test
  void aShorterRetentionPurgesSooner() {
    WorkflowEntity workflow = savedWorkflow("short-retention-wf");
    WorkflowRunEntity run = finishedRun(workflow.getId(), 100);
    setRunRetention("90");

    watcher.purgeExpiredRuns();

    assertThat(workflowRunRepository.existsById(run.getId())).isFalse();
  }

  private WorkflowEntity savedWorkflow(String name) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName(name);
    workflow.setStatus(WorkflowStatus.active);
    return workflowRepository.save(workflow);
  }

  private WorkflowRunEntity finishedRun(String workflowRef, int daysOld) {
    WorkflowRunEntity run =
        savedWorkflowRun(workflowRef, RunStatus.succeeded, RunPhase.completed);
    run.setCreationDate(daysAgo(daysOld));
    return workflowRunRepository.save(run);
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

  private static Date daysAgo(int days) {
    return new Date(System.currentTimeMillis() - days * DAY_MILLIS);
  }

  /** Sets, or with null removes, the retention key of the shared "workflowrun" settings document. */
  private void setRunRetention(String value) {
    SettingEntity settings = settingsRepository.findOneByKey("workflowrun");
    if (settings == null) {
      settings = new SettingEntity();
      settings.setKey("workflowrun");
      settings.setName("Run limits");
      settings.setConfig(new ArrayList<>());
    }
    List<SettingConfig> config = new ArrayList<>(settings.getConfig());
    config.removeIf(entry -> "retention.days".equals(entry.getKey()));
    if (value != null) {
      SettingConfig entry = new SettingConfig();
      entry.setKey("retention.days");
      entry.setType("number");
      entry.setValue(value);
      config.add(entry);
    }
    settings.setConfig(config);
    settingsRepository.save(settings);
  }
}
