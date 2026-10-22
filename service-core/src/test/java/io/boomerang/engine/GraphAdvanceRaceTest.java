package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.WorkflowTaskDependency;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Graph-advance race on an AND-join: when both parents of a join complete concurrently, each
 * winner's advance may queue the join, but the admission Compare-And-Set admits it exactly once -
 * one execution, one side effect.
 */
class GraphAdvanceRaceTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskRunService taskRunService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void joinIsAdmittedOnceWhenBothParentsCompleteConcurrently() {
    WorkflowRunEntity wfRun = savedWorkflowRun("join-race-wf", RunStatus.running, RunPhase.running);

    TaskRunEntity start =
        savedTaskRun(
            "start",
            TaskType.start,
            RunStatus.succeeded,
            RunPhase.completed,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    TaskRunEntity parentA = savedRunningTask("parent-a", wfRun, "start");
    TaskRunEntity parentB = savedRunningTask("parent-b", wfRun, "start");
    TaskRunEntity join =
        savedTaskRun(
            "join-gate",
            TaskType.approval,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    join.setDependencies(List.of(dependencyOn("parent-a"), dependencyOn("parent-b")));
    taskRunRepository.save(join);
    TaskRunEntity end =
        savedTaskRun(
            "end",
            TaskType.end,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    end.setDependencies(List.of(dependencyOn("join-gate")));
    taskRunRepository.save(end);

    // Both parents end together; each async advance evaluates the join.
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(parentA.getId(), Optional.of(endRequest));
    taskRunService.end(parentB.getId(), Optional.of(endRequest));

    // The join runs exactly once: a single approval record, and the gate waits on user action.
    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(join.getId()));
    awaitEngine("the join to be admitted and its approval created")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    awaitEngine("no duplicate approval to appear for the join")
        .during(Duration.ofSeconds(2))
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    assertEquals(1, mongoTemplate.count(byTaskRunRef, ActionEntity.class));
    assertEquals(
        RunStatus.waiting, taskRunRepository.findById(join.getId()).orElseThrow().getStatus());
  }

  private TaskRunEntity savedRunningTask(String name, WorkflowRunEntity wfRun, String dependsOn) {
    TaskRunEntity task =
        savedTaskRun(
            name,
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setDependencies(List.of(dependencyOn(dependsOn)));
    return taskRunRepository.save(task);
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }
}
