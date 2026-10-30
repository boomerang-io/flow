package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.entity.TaskLockEntity;
import io.boomerang.workflow.WorkflowRetentionService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A run's locks go with the run: a run that completes, by a cancel here, holding a lock it never
 * released frees it at once, so the next acquire on the key succeeds on its first attempt; a purge
 * of a run in flight frees them too; and a lock another run holds is never touched.
 */
class TaskLockReleaseTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRetentionService workflowRetentionService;
  @Autowired private MongoTemplate mongoTemplate;

  private String acquireTask;
  private String sleepTask;

  @BeforeEach
  void engineRunTasks() {
    acquireTask = task("lock-acquire", TaskType.acquirelock, "key", "timeout");
    sleepTask = task("lock-sleep", TaskType.sleep, "duration");
  }

  @Test
  void aCancelledRunFreesItsLockAndTheNextAcquireSucceedsAtOnce() {
    String key = "release-" + System.nanoTime();
    String holder = run(holdingWorkflow("lock-holder", key));
    awaitEngine("the run holds the lock and sleeps")
        .untilAsserted(() -> assertThat(locksOf(holder)).hasSize(1));
    String other = run(holdingWorkflow("lock-other", "other-" + System.nanoTime()));
    awaitEngine("another run holds its own key")
        .untilAsserted(() -> assertThat(locksOf(other)).hasSize(1));

    workflowRunService.cancel(holder);

    awaitEngine("the cancelled run's lock is released")
        .untilAsserted(
            () -> {
              assertThat(workflowRunRepository.findById(holder).orElseThrow().getPhase())
                  .isEqualTo(RunPhase.completed);
              assertThat(locksOf(holder)).isEmpty();
            });
    assertThat(locksOf(other)).as("another run's lock is untouched").hasSize(1);

    String next = run(holdingWorkflow("lock-next", key));
    awaitEngine("the next run acquires the key on its first attempt")
        .untilAsserted(
            () -> {
              TaskRunEntity acquire =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("acquire", next).orElseThrow();
              assertThat(acquire.getStatus()).isEqualTo(RunStatus.succeeded);
              assertThat(acquire.getStatusMessage()).isNull();
            });
    assertThat(locksOf(next)).hasSize(1);
  }

  @Test
  void aPurgedRunFreesItsLock() {
    String key = "purge-" + System.nanoTime();
    String holder = run(holdingWorkflow("lock-purged", key));
    awaitEngine("the run holds the lock")
        .untilAsserted(() -> assertThat(locksOf(holder)).hasSize(1));

    workflowRetentionService.purge(holder);

    awaitEngine("the purge released the lock")
        .untilAsserted(() -> assertThat(locksOf(holder)).isEmpty());
  }

  private List<TaskLockEntity> locksOf(String runId) {
    return mongoTemplate.find(
        Query.query(Criteria.where("workflowRunRef").is(runId)), TaskLockEntity.class);
  }

  private String run(Workflow workflow) {
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(runId, Optional.empty());
    return runId;
  }

  // start -> acquire(key, long lease) -> hold (a long sleep) -> end: the lock is never released.
  private Workflow holdingWorkflow(String name, String key) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask acquire = node("acquire", TaskType.acquirelock, acquireTask, "start");
    acquire.setParams(
        new LinkedList<>(List.of(new RunParam("key", key), new RunParam("timeout", "600000"))));
    WorkflowTask hold = node("hold", TaskType.sleep, sleepTask, "acquire");
    hold.setParams(new LinkedList<>(List.of(new RunParam("duration", "600000"))));
    WorkflowTask end = node("end", TaskType.end, null, "hold");
    workflow.setTasks(new ArrayList<>(List.of(start, acquire, hold, end)));
    return workflow;
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(dependsOn);
    task.setDependencies(new LinkedList<>(List.of(dependency)));
    return task;
  }

  private String task(String name, TaskType type, String... params) {
    Task task = new Task();
    task.setName(name + "-" + System.nanoTime());
    task.setType(type);
    List<AbstractParam> declared = new ArrayList<>();
    for (String param : params) {
      AbstractParam declaredParam = new AbstractParam();
      declaredParam.setName(param);
      declaredParam.setType("text");
      declared.add(declaredParam);
    }
    task.getSpec().setParams(declared);
    return taskService.create(task).getId();
  }
}
