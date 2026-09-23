package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A task's effective timeout is the smallest of three ceilings, all in minutes: the platform
 * setting stamped as {@code boomerang.io/task-timeout}, the task's own declared timeout, and the
 * run's timeout. 0 anywhere means "unguarded" and imposes no ceiling.
 *
 * <p>The run is the third ceiling because of decision 0063: a guard must cover the work beneath
 * it, so a task must never outlive the run containing it - otherwise the watcher's run reap kills
 * a task that still believes it has budget left.
 *
 * <p>These drive {@link DAGUtility#createTaskList} directly on a run entity built here rather
 * than going through {@code WorkflowService.submit}. Two reasons: the submit path overwrites the
 * run's timeout from the workspace quota, so a test could not pin a run at 30 and a task at 45
 * through it; and {@code createTaskList} materialises without persisting, so the assertion is on
 * exactly the value the clamp produces. It is the same entity the engine hands the method in
 * production - {@code WorkflowExecutionService.queue} re-reads the persisted run by id and passes
 * it straight in.
 */
class TaskTimeoutClampedToRunTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private DAGUtility dagUtility;

  /**
   * The fresh-install default: no platform ceiling (the seed ships {@code task}/{@code
   * default.timeout} at 0, so no annotation is stamped) and a task that declares nothing. The task
   * inherits the run's 30.
   */
  @Test
  void noPlatformCeilingAndNoDeclaredTimeoutInheritsTheRun() {
    assertEquals(30L, materialise("inherit-run", null, null, 30L));
  }

  /** The seeded-90 case an upgraded install still carries: the run's 30 wins over it. */
  @Test
  void runTimeoutClampsAPlatformDefaultLargerThanIt() {
    assertEquals(30L, materialise("clamp-platform", 90L, null, 30L));
  }

  /** A task that asks for less than everything around it gets what it asked for. */
  @Test
  void declaredTimeoutBelowBothCeilingsIsKept() {
    assertEquals(10L, materialise("declared-wins", 90L, 10L, 30L));
  }

  /**
   * The case decision 0063 forbids: a task declaring 45 inside a 30-minute run. The run is the
   * ceiling, so the task gets 30 and the run reap can never fire under a task that still has
   * budget.
   */
  @Test
  void declaredTimeoutAboveTheRunIsClampedToTheRun() {
    assertEquals(30L, materialise("declared-above-run", 90L, 45L, 30L));
  }

  /** An unguarded run imposes no ceiling - the platform default stands on its own. */
  @Test
  void unguardedRunLeavesThePlatformDefaultInPlace() {
    assertEquals(90L, materialise("unguarded-run", 90L, null, 0L));
  }

  /** Nothing guarded anywhere stays 0, the engine's "no deadline" value. */
  @Test
  void unguardedRunAndNoCeilingsStaysUnguarded() {
    assertEquals(0L, materialise("unguarded-everywhere", null, null, 0L));
  }

  /**
   * Builds a one-task workflow, materialises its TaskRuns against a run entity carrying the given
   * platform annotation and run timeout, and returns the task's resolved timeout.
   *
   * @param platformDefault stamped as {@code boomerang.io/task-timeout}; null stamps no annotation,
   *     which is what {@code WorkflowService.internalSubmit} now does for a 0 or blank setting
   * @param declaredTimeout the Workflow Task's own timeout; null declares none
   * @param runTimeout the run's timeout; 0 means unguarded
   */
  private long materialise(
      String name, Long platformDefault, Long declaredTimeout, long runTimeout) {
    seedRelationshipRoot();

    // An explicit image keeps createTaskList off the task-default-image annotation fallback,
    // which is absent on a run entity built here.
    Task template = new Task();
    template.setName(name + "-task");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    WorkflowTask work = workflowTask("work", TaskType.template, templateId, "start");
    work.setTimeout(declaredTimeout);
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            work,
            workflowTask("end", TaskType.end, null, "work")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    WorkflowRevisionEntity revision =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId).orElseThrow();

    // A real id, not null: createTaskList looks the run's existing TaskRuns up by it, and a null
    // ref matches every TaskRun in the shared Testcontainers database that has none.
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(new ObjectId().toHexString());
    run.setWorkflowRef(workflowId);
    run.setWorkflowRevisionRef(revision.getId());
    run.setTimeout(runTimeout);
    Map<String, Object> annotations = new HashMap<>();
    if (platformDefault != null) {
      annotations.put("boomerang.io/task-timeout", platformDefault.toString());
    }
    run.setAnnotations(annotations);

    List<TaskRunEntity> tasks = dagUtility.createTaskList(revision, run);
    return tasks.stream()
        .filter(t -> "work".equals(t.getName()))
        .findFirst()
        .orElseThrow()
        .getTimeout();
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
