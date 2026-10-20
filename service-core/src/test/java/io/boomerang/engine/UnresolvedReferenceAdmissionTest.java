package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A Flow reference naming nothing declared fails the task at admission, with the typed reason
 * ParamUnresolved and a message naming the task, the field, each reference and why. A reference to
 * something declared that has no value resolves to an empty string. Shell command substitution and
 * values inserted as written are not references and do not fail it.
 */
class UnresolvedReferenceAdmissionTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private ParameterManager parameterManager;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;

  @MockitoSpyBean private DAGUtility dagUtility;

  private WorkflowRunEntity wfRun;

  @BeforeEach
  void graphAllowsTheTask() {
    doAnswer(invocation -> true).when(dagUtility).canRunTask(anyList(), any());
    wfRun = savedWorkflowRun("unresolved-ref-wf", RunStatus.running, RunPhase.running);
  }

  @Test
  void aTypoInTheScriptFailsTheTaskAtAdmission() {
    TaskRunEntity task = deployTask(List.of(param("registryToken", "token-1")));
    task.getSpec().setScript("login $(params.registryTokn)");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': script references $(params.registryTokn): no such param 'registryTokn'",
        after.getStatusMessage());
  }

  @Test
  void aMissingGlobalInAnEnvValueAndAParamFailTheTask() {
    TaskRunEntity task = deployTask(List.of(param("url", "$(params.hots)/api")));
    task.getSpec()
        .setEnvs(
            new LinkedList<>(
                List.of(new TaskEnvVar("REGION", "$(global.params.missing) $(global.params.gone)"))));
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': param 'url' references $(params.hots): no such param 'hots'; env 'REGION'"
            + " references $(global.params.missing): no such global param 'missing',"
            + " $(global.params.gone): no such global param 'gone'",
        after.getStatusMessage());
  }

  // A typo in a task name: the task is not in this workflow.
  @Test
  void aTaskNotInTheWorkflowFailsTheTask() {
    TaskRunEntity task = deployTask(List.of());
    task.getSpec().setScript("deploy $(tasks.biuld.results.digest)");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': script references $(tasks.biuld.results.digest): task 'biuld' is not in"
            + " this workflow",
        after.getStatusMessage());
  }

  // A typo in a result name, on a catalogue task that declares its results.
  @Test
  void aResultTheTemplateDoesNotDeclareFailsTheTask() {
    Task template = templateDeclaring("digest");
    upstream("build", template, RunStatus.succeeded, new RunResult("digest", "sha256:abc"));
    TaskRunEntity task = deployTask(List.of());
    task.getSpec().setScript("deploy $(tasks.build.results.digest) $(tasks.build.results.nope)");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': script references $(tasks.build.results.nope): task 'build' declares no"
            + " result 'nope'",
        after.getStatusMessage());
  }

  // Declared but without a value is an empty string: a result of a task whose template declares
  // none, an optional param, and an object path that matches nothing on a declared object param.
  @Test
  void declaredReferencesWithoutAValueAreEmptyAndTheTaskAdmitted() {
    upstream("build", templateDeclaring(), RunStatus.succeeded, new RunResult("digest", "abc"));
    TaskRunEntity task =
        deployTask(
            List.of(
                new RunParam("optional", null, ParamType.string),
                new RunParam("cfg", Map.of("region", "eu"), ParamType.object)));
    task.getSpec()
        .setScript(
            "[$(tasks.build.results.digest)][$(tasks.build.results.other)][$(params.optional)]"
                + "[$(params.cfg.region)][$(params.cfg.zone)]");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitAdmitted(task.getId());
    assertEquals(
        "[abc][][][eu][]", parameterManager.fillSpec(currentRun(), after).getScript());
  }

  // After a decision only one branch runs; the join still references both. The skipped branch's
  // result is declared, so it is an empty string, and the run succeeds.
  @Test
  void aDecisionJoinReferencingTheSkippedBranchIsAdmittedAndTheRunSucceeds() {
    doCallRealMethod().when(dagUtility).canRunTask(anyList(), any());
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Task decision = new Task();
    decision.setName("join-decision-" + suffix);
    decision.setType(TaskType.decision);
    decision.getSpec().setImage("busybox:latest");
    String decisionId = taskService.create(decision).getId();
    String buildId = templateDeclaring("url").getId();
    String notifyId = templateDeclaring().getId();

    WorkflowTask notify = node("notify", TaskType.template, notifyId, null, "build-a", "build-b");
    notify
        .getParams()
        .add(
            new RunParam(
                "message",
                "a=$(tasks.build-a.results.url) b=$(tasks.build-b.results.url)",
                ParamType.string));
    WorkflowTask decide = node("decide", TaskType.decision, decisionId, null, "start");
    decide.getParams().add(new RunParam("value", "a", ParamType.string));
    Workflow workflow = new Workflow();
    workflow.setName("decision-join-" + suffix);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null, null),
                decide,
                node("build-a", TaskType.template, buildId, "a", "decide"),
                node("build-b", TaskType.template, buildId, "b", "decide"),
                notify,
                node("end", TaskType.end, null, null, "notify"))));
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("run ready")
        .until(
            () -> RunStatus.ready.equals(workflowRunRepository.findById(runId).orElseThrow().getStatus()));
    workflowRunService.start(runId, Optional.empty());

    String buildA = awaitStatus("build-a", runId, RunStatus.ready).getId();
    taskRunService.start(buildA, Optional.empty());
    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.getResults().add(new RunResult("url", "https://a.example"));
    taskRunService.end(buildA, Optional.of(end));

    TaskRunEntity joined = awaitStatus("notify", runId, RunStatus.ready);
    assertEquals(
        RunStatus.skipped,
        taskRunRepository.findFirstByNameAndWorkflowRunRef("build-b", runId).orElseThrow().getStatus());
    assertEquals(
        "a=https://a.example b=",
        joined.getParams().stream()
            .filter(p -> "message".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());
    taskRunService.start(joined.getId(), Optional.empty());
    TaskRunEndRequest notified = new TaskRunEndRequest();
    notified.setStatus(RunStatus.succeeded);
    taskRunService.end(joined.getId(), Optional.of(notified));
    awaitEngine("run succeeded")
        .until(
            () ->
                RunStatus.succeeded.equals(
                    workflowRunRepository.findById(runId).orElseThrow().getStatus()));
  }

  // Shell command substitution is not a Flow reference: it stays text and the task is admitted.
  @Test
  void shellCommandSubstitutionIsUntouchedAndTheTaskAdmitted() {
    TaskRunEntity task = deployTask(List.of(param("greeting", "hi")));
    String script = "echo $(date) $(cat /etc/hostname) $(params.greeting)";
    task.getSpec().setScript(script);
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitAdmitted(task.getId());
    assertEquals(script, after.getSpec().getScript(), "the stored spec keeps its references");
    assertEquals(
        "echo $(date) $(cat /etc/hostname) hi",
        parameterManager.fillSpec(currentRun(), after).getScript());
  }

  // A webhook's payload is inserted as written: reference text inside it is not scanned.
  @Test
  void aSuppliedValueContainingReferenceTextIsAdmittedAndInsertedAsWritten() {
    wfRun.setTrigger(TriggerEnum.webhook.getTrigger());
    wfRun.setParams(new LinkedList<>(List.of(param("payload", "$(params.nothing)"))));
    workflowRunRepository.save(wfRun);
    TaskRunEntity task = deployTask(List.of(param("p", "$(params.payload)")));
    task.getSpec().setScript("echo $(params.p)");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitAdmitted(task.getId());
    assertEquals("$(params.nothing)", after.getParams().get(0).getValue());
    assertEquals(
        "echo $(params.nothing)", parameterManager.fillSpec(currentRun(), after).getScript());
  }

  // $(params.x) reads the nearest layer, which for a task param x is x itself.
  @Test
  void aTaskParamReferencingItselfFailsTheTask() {
    wfRun.setParams(new LinkedList<>(List.of(param("analysisId", "abc"))));
    workflowRunRepository.save(wfRun);
    TaskRunEntity task = deployTask(List.of(param("analysisId", "$(params.analysisId)")));
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': param 'analysisId' references $(params.analysisId): refers to itself; use"
            + " $(workflow.params.analysisId) for the workflow's param",
        after.getStatusMessage());
  }

  @Test
  void taskParamsReferencingEachOtherFailTheTask() {
    TaskRunEntity task = deployTask(List.of(param("x", "$(params.y)-a"), param("y", "$(params.X)")));
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': param 'x' references $(params.y): params 'x' and 'y' reference each other"
            + " in a cycle; param 'y' references $(params.X): params 'y' and 'x' reference each"
            + " other in a cycle",
        after.getStatusMessage());
  }

  @Test
  void theWorkflowScopeReachesPastATaskParamOfTheSameName() {
    wfRun.setParams(new LinkedList<>(List.of(param("analysisId", "abc"))));
    workflowRunRepository.save(wfRun);
    TaskRunEntity task =
        deployTask(
            List.of(
                param("analysisId", "$(workflow.params.analysisId)"),
                param("label", "$(params.analysisId)-v")));
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitAdmitted(task.getId());
    assertEquals("abc", after.getParams().get(0).getValue());
    assertEquals("abc-v", after.getParams().get(1).getValue());
  }

  // Reference text inside a supplied value is a value, so it closes no cycle.
  @Test
  void aSuppliedValueThatLooksLikeASelfReferenceIsInsertedAsWritten() {
    wfRun.setTrigger(TriggerEnum.webhook.getTrigger());
    wfRun.setParams(new LinkedList<>(List.of(param("payload", "$(params.p)"))));
    workflowRunRepository.save(wfRun);
    TaskRunEntity task = deployTask(List.of(param("p", "$(params.payload)")));
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitAdmitted(task.getId());
    assertEquals("$(params.p)", after.getParams().get(0).getValue());
  }

  private TaskRunEntity deployTask(List<RunParam> params) {
    TaskRunEntity task =
        savedTaskRun(
            "deploy",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setParams(new LinkedList<>(params));
    return task;
  }

  // A global template Task declaring these results; none declares no results.
  private Task templateDeclaring(String... results) {
    Task template = new Task();
    template.setName("ref-check-" + UUID.randomUUID().toString().substring(0, 8));
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    for (String name : results) {
      ResultSpec result = new ResultSpec();
      result.setName(name);
      template.getSpec().getResults().add(result);
    }
    return taskService.create(template);
  }

  // A finished upstream TaskRun of the given catalogue task, as createTaskList records it.
  private void upstream(String name, Task template, RunStatus status, RunResult result) {
    TaskRunEntity build =
        savedTaskRun(
            name,
            TaskType.template,
            status,
            RunPhase.completed,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    build.setTaskRef(template.getId());
    build.setTaskVersion(template.getVersion());
    build.setResults(new LinkedList<>(List.of(result)));
    taskRunRepository.save(build);
  }

  private static WorkflowTask node(
      String name, TaskType type, String taskRef, String decisionCondition, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      if ("decide".equals(dep)) {
        dependency.setDecisionCondition(decisionCondition);
      }
      task.getDependencies().add(dependency);
    }
    return task;
  }

  private TaskRunEntity awaitStatus(String name, String runId, RunStatus status) {
    awaitEngine(name + " " + status)
        .until(
            () ->
                taskRunRepository
                    .findFirstByNameAndWorkflowRunRef(name, runId)
                    .map(t -> status.equals(t.getStatus()))
                    .orElse(false));
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
  }

  private TaskRunEntity queueAndAwaitCompleted(String taskId) {
    taskExecutionService.queue(taskId);
    awaitEngine("the task to fail at admission")
        .until(
            () ->
                RunPhase.completed.equals(
                    taskRunRepository.findById(taskId).orElseThrow().getPhase()));
    return taskRunRepository.findById(taskId).orElseThrow();
  }

  private TaskRunEntity queueAndAwaitAdmitted(String taskId) {
    taskExecutionService.queue(taskId);
    awaitEngine("the task to be admitted")
        .until(
            () ->
                RunStatus.ready.equals(taskRunRepository.findById(taskId).orElseThrow().getStatus()));
    return taskRunRepository.findById(taskId).orElseThrow();
  }

  private WorkflowRunEntity currentRun() {
    return workflowRunRepository.findById(wfRun.getId()).orElseThrow();
  }

  private static RunParam param(String name, Object value) {
    return new RunParam(name, value, ParamType.string);
  }
}
