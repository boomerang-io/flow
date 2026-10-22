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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The database commands each dispatcher request makes on the request's own thread, for one task
 * taken through claim, start, heartbeat and a mid-graph end - the cost the dispatcher integration
 * puts on MongoDB. Work a request hands to another thread (the execute after a start, the queueing after
 * an end) is not counted. A change to these paths updates the expected lists here, so every saving
 * - or regression - is visible in review.
 */
class DispatcherRequestDatabaseCommandsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "db-commands-task";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private ClaimFilterService claimFilterService;
  @Autowired private ParamLayerService paramLayerService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void aTaskThroughClaimStartHeartbeatAndAMidGraphEnd() {
    Ready ready = readyTask("db-commands");
    String taskRunId = ready.taskRunId();
    String dispatcherId = ready.dispatcherId();

    Map<String, Recorded<?>> recorded = new LinkedHashMap<>();
    Recorded<List<TaskRun>> poll =
        DatabaseCommands.record(
            () -> dispatcherService.getTaskQueue(dispatcherId, 1, null, null, ready.label()).getBody());
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
    awaitEndHandedOn(ready);

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

  /**
   * Every TaskRun in the run carries a 64 KB spec, and only the two reads of the ending TaskRun
   * itself return one whole: the completion returns what it publishes and the graph advance reads
   * the graph's fields of the ended task's neighbourhood, so the end does not read back the run's
   * specs, params and results.
   */
  @Test
  void anEndReadsNoOtherTaskRunWhole() {
    Ready ready = readyTask("db-bytes");
    String taskRunId = ready.taskRunId();
    String dispatcherId = ready.dispatcherId();
    assertTrue(
        dispatcherService.getTaskQueue(dispatcherId, 1, null, null, ready.label()).getBody().stream()
            .anyMatch(t -> taskRunId.equals(t.getId())),
        "the poll claims the task");
    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(dispatcherId);
    taskRunService.start(taskRunId, Optional.of(start));
    awaitEngine("the task to be running")
        .until(() -> RunPhase.running.equals(current(taskRunId).getPhase()));
    mongoTemplate.updateMulti(
        Query.query(Criteria.where("workflowRunRef").is(ready.runId())),
        new Update().set("spec.script", "x".repeat(LARGE_FIELD_BYTES)),
        TaskRunEntity.class);

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setDispatcherRef(dispatcherId);
    Recorded<?> recorded =
        DatabaseCommands.record(() -> taskRunService.end(taskRunId, Optional.of(end)));
    awaitEndHandedOn(ready);

    System.out.printf(
        "DB REPLY BYTES | end | %s%n",
        recorded.commands().stream().map(c -> c + " " + c.replyBytes()).toList());
    assertEquals(
        List.of("find task_runs", "find task_runs"),
        recorded.commands().stream()
            .filter(c -> c.replyBytes() > LARGE_FIELD_BYTES)
            .map(c -> c.toString().replace(" flowtest_", " "))
            .toList(),
        "only the two reads of the ending TaskRun return a TaskRun whole");
  }

  /**
   * An end reads the ended task's dependants and what they depend on, not the run's graph: in a
   * chain of 200 tasks the end of the first returns 4 task run documents - the ending task twice
   * whole, then its one dependant and, with that dependant's dependencies, itself once more - where
   * the graph read returned the run's 202, in a find and a getMore. Each dependant and each task it
   * depends on is one more document, never the run's size.
   */
  @Test
  void anEndOfA200TaskRunReadsItsDependantsNotTheGraph() {
    Ready ready = readyTask("db-chain", 200);
    String taskRunId = ready.taskRunId();
    String dispatcherId = ready.dispatcherId();
    assertTrue(
        dispatcherService
            .getTaskQueue(dispatcherId, 1, null, null, ready.label())
            .getBody()
            .stream()
            .anyMatch(t -> taskRunId.equals(t.getId())),
        "the poll claims the task");
    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(dispatcherId);
    taskRunService.start(taskRunId, Optional.of(start));
    awaitEngine("the task to be running")
        .until(() -> RunPhase.running.equals(current(taskRunId).getPhase()));

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setDispatcherRef(dispatcherId);
    Recorded<?> recorded =
        DatabaseCommands.record(() -> taskRunService.end(taskRunId, Optional.of(end)));
    awaitEndHandedOn(ready);

    System.out.printf(
        "DB DOCUMENTS | end of 1 of 200 | %d task_runs documents | %s%n",
        recorded.documentsRead("task_runs"),
        recorded.commands().stream().map(c -> c + " " + c.documents()).toList());
    assertEquals(
        4,
        recorded.documentsRead("task_runs"),
        "the end reads the ending task, its dependant and the dependant's dependencies");
  }

  private static final int LARGE_FIELD_BYTES = 64 * 1024;

  private record Ready(String runId, String taskRunId, String dispatcherId, String label) {}

  private Ready readyTask(String name) {
    return readyTask(name, 2);
  }

  // A run of start -> work -> next -> ... -> end, a chain of the given number of tasks, whose first
  // task is ready for a dispatcher, with the poll filter and the run's parameter layers already
  // cached, so every recording is of the warm path a dispatcher sees in steady state. At least two
  // tasks, so the measured end is mid-graph: ending the last task would also complete the run on
  // the request thread, and what that does depends on state other tests leave behind.
  private Ready readyTask(String name, int tasks) {
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    WorkspaceRequest workspace = new WorkspaceRequest();
    workspace.setName(name + "-ws");
    workspace.setDisplayName(name + "-ws");
    workspaceService.create(workspace);
    Workflow workflow = new Workflow();
    workflow.setName(name + "-wf");
    LinkedList<WorkflowTask> chain = new LinkedList<>();
    chain.add(node("start", TaskType.start, null, null));
    chain.add(node("work", TaskType.template, TASK_SLUG, "start"));
    chain.add(node("next", TaskType.template, TASK_SLUG, "work"));
    for (int i = 3; i <= tasks; i++) {
      chain.add(node("task" + i, TaskType.template, TASK_SLUG, chain.getLast().getName()));
    }
    chain.add(node("end", TaskType.end, null, chain.getLast().getName()));
    workflow.setTasks(chain);
    workflow.getLabels().put(name, "yes");
    workflowService.create(name + "-ws", workflow);
    WorkflowSubmitRequest submit = new WorkflowSubmitRequest();
    submit.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(name + "-ws", name + "-wf", submit, true);
    awaitEngine("the task to be ready for a dispatcher")
        .until(() -> isReady("work", run.getId()));
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).orElseThrow().getId();
    String dispatcherId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                name + "-dispatcher", name + ".local", List.of("template")));
    String label = name + "=yes";
    claimFilterService.resolve(List.of(TaskType.template), null, null, label);
    WorkflowRunEntity runEntity = workflowRunRepository.findById(run.getId()).orElseThrow();
    paramLayerService.buildParamLayers(
        runEntity.getWorkflowRef(), paramLayerService.getRevision(runEntity.getWorkflowRevisionRef()));
    return new Ready(run.getId(), taskRunId, dispatcherId, label);
  }

  // The end completes the task; the next task is queued off the request thread, so let it land
  // before the next test.
  private void awaitEndHandedOn(Ready ready) {
    awaitEngine("the task to be completed")
        .until(() -> RunPhase.completed.equals(current(ready.taskRunId()).getPhase()));
    awaitEngine("the next task to be ready").until(() -> isReady("next", ready.runId()));
  }

  private boolean isReady(String name, String runId) {
    return taskRunRepository
        .findFirstByNameAndWorkflowRunRef(name, runId)
        .map(t -> RunStatus.ready.equals(t.getStatus()))
        .orElse(false);
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
              "find task_runs",
              "aggregate task_runs"));
}
