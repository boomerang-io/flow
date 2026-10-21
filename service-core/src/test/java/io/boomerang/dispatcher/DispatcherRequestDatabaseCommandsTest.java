package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.engine.DatabaseCommands.Recorded;
import io.boomerang.workflow.ParamLayerService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database commands each dispatcher request makes on the request's own thread, for one task
 * taken through claim, start, heartbeat and a mid-graph end - the cost the dispatcher integration
 * puts on MongoDB. Work a request hands to another thread (the execute after a start, the queueing after
 * an end) is not counted. A change to these paths updates the expected lists here, so every saving
 * - or regression - is visible in review.
 */
class DispatcherRequestDatabaseCommandsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "db-commands-task";
  private static final String LABEL = "db-commands-test";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private ClaimFilterService claimFilterService;
  @Autowired private ParamLayerService paramLayerService;

  @Test
  void aTaskThroughClaimStartHeartbeatAndAMidGraphEnd() {
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    WorkspaceRequest workspace = new WorkspaceRequest();
    workspace.setName("db-commands-ws");
    workspace.setDisplayName("db-commands-ws");
    workspaceService.create(workspace);
    // Two tasks, so the measured end is mid-graph: ending the last task would also complete the
    // run on the request thread, and what that does depends on state other tests leave behind.
    Workflow workflow = new Workflow();
    workflow.setName("db-commands-wf");
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null, null),
                node("work", TaskType.template, TASK_SLUG, "start"),
                node("next", TaskType.template, TASK_SLUG, "work"),
                node("end", TaskType.end, null, "next"))));
    workflow.getLabels().put(LABEL, "yes");
    workflowService.create("db-commands-ws", workflow);
    WorkflowSubmitRequest submit = new WorkflowSubmitRequest();
    submit.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit("db-commands-ws", "db-commands-wf", submit, true);
    awaitEngine("the task to be ready for a dispatcher")
        .until(
            () ->
                taskRunRepository
                    .findFirstByNameAndWorkflowRunRef("work", run.getId())
                    .map(t -> RunStatus.ready.equals(t.getStatus()))
                    .orElse(false));
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).orElseThrow().getId();
    String dispatcherId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "db-commands-dispatcher", "db-commands.local", List.of("template")));

    // The poll filter's resolution and the run's parameter layers are cached; read them first so
    // every recording is of the warm path a dispatcher sees in steady state.
    claimFilterService.resolve(List.of(TaskType.template), null, null, LABEL + "=yes");
    WorkflowRunEntity runEntity = workflowRunRepository.findById(run.getId()).orElseThrow();
    paramLayerService.buildParamLayers(
        runEntity.getWorkflowRef(), paramLayerService.getRevision(runEntity.getWorkflowRevisionRef()));

    Map<String, Recorded<?>> recorded = new LinkedHashMap<>();
    Recorded<List<TaskRun>> poll =
        DatabaseCommands.record(
            () -> dispatcherService.getTaskQueue(dispatcherId, 1, null, null, LABEL + "=yes").getBody());
    recorded.put("poll that claims one task", poll);
    assertTrue(
        poll.result() != null && poll.result().stream().anyMatch(t -> taskRunId.equals(t.getId())),
        "the poll claims the task");

    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(dispatcherId);
    recorded.put(
        "start", DatabaseCommands.record(() -> taskRunService.start(taskRunId, Optional.of(start))));
    awaitEngine("the task to be running")
        .until(() -> RunPhase.running.equals(current(taskRunId).getPhase()));

    recorded.put(
        "heartbeat",
        DatabaseCommands.record(() -> dispatcherService.heartbeat(dispatcherId, List.of(taskRunId))));

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setDispatcherRef(dispatcherId);
    recorded.put(
        "end", DatabaseCommands.record(() -> taskRunService.end(taskRunId, Optional.of(end))));
    awaitEngine("the task to be completed")
        .until(() -> RunPhase.completed.equals(current(taskRunId).getPhase()));
    // The next task is queued off the request thread; let it land so it does not run into the next
    // test.
    awaitEngine("the next task to be ready")
        .until(
            () ->
                taskRunRepository
                    .findFirstByNameAndWorkflowRunRef("next", run.getId())
                    .map(t -> RunStatus.ready.equals(t.getStatus()))
                    .orElse(false));

    recorded.forEach(
        (operation, r) ->
            System.out.printf(
                "DB COMMANDS | %-26s | %2d commands | %7d reply bytes | %s%n",
                operation, r.commands().size(), r.replyBytes(), r.names()));

    recorded.forEach(
        (operation, r) ->
            assertEquals(
                EXPECTED.get(operation),
                r.names().stream().map(name -> name.replace(" flowtest_", " ")).toList(),
                operation));
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  private TaskRunEntity current(String id) {
    return taskRunRepository.findById(id).orElseThrow();
  }

  // The commands each request issues, in order, as "command collection" without the collection
  // prefix.
  private static final Map<String, List<String>> EXPECTED =
      Map.of(
          "poll that claims one task",
          List.of(
              "findAndModify dispatchers",
              "find task_runs",
              "findAndModify task_runs",
              "find workflow_runs",
              "find task_runs",
              "find task_runs"),
          "start",
          List.of("find task_runs", "find task_runs", "find workflow_runs", "find task_runs"),
          "heartbeat",
          List.of("update dispatchers", "update task_runs"),
          "end",
          List.of(
              "find task_runs",
              "update task_runs",
              "find task_runs",
              "find workflow_runs",
              "findAndModify task_runs",
              "find workflow_runs",
              "find task_runs",
              "aggregate task_runs"));
}
