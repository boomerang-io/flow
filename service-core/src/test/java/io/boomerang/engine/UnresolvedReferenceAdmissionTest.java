package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A Flow reference that does not resolve fails the task at admission, with the typed reason
 * ParamUnresolved and a message naming the task, the field and each reference. Shell command
 * substitution and values inserted as written are not references and do not fail it.
 */
class UnresolvedReferenceAdmissionTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private ParameterManager parameterManager;

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
        "Task 'deploy': script references $(params.registryTokn) which does not resolve",
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
        "Task 'deploy': param 'url' references $(params.hots) which does not resolve; env 'REGION'"
            + " references $(global.params.missing), $(global.params.gone) which do not resolve",
        after.getStatusMessage());
  }

  // A task that ran but produced no such result: the reference does not resolve, as a missing
  // param does (before, it was left as written).
  @Test
  void aResultTheUpstreamTaskDidNotProduceFailsTheTask() {
    TaskRunEntity build =
        savedTaskRun(
            "build",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    build.setResults(List.of(new RunResult("digest", "sha256:abc")));
    taskRunRepository.save(build);
    TaskRunEntity task = deployTask(List.of());
    task.getSpec().setScript("deploy $(tasks.build.results.digest) $(tasks.build.results.nope)");
    taskRunRepository.save(task);

    TaskRunEntity after = queueAndAwaitCompleted(task.getId());

    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals("ParamUnresolved", after.getStatusReason());
    assertEquals(
        "Task 'deploy': script references $(tasks.build.results.nope) which does not resolve",
        after.getStatusMessage());
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
