package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Full WorkflowRun lifecycle: submit, then drive to completion via exactly the callbacks a
 * registered agent makes (mirroring service-dispatcher's QueueService): startWorkflow, startTask,
 * endTask, finalizeWorkflow. Regression tripwire for DAGUtility / TaskExecutionService changes
 * (standing gate G1) - this test must stay green through the whole v5 refactor.
 */
class WorkflowRunLifecycleTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;

  @Test
  void submittedRunCompletesThroughAgentCallbacks() {
    // Task template. The explicit image avoids DAGUtility.createTaskList's
    // task-default-image annotation fallback, which NPEs when the annotation is absent.
    Task template = new Task();
    template.setName("lifecycle-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();
    assertNotNull(templateId);

    // Workflow: start -> echo (template) -> end.
    Workflow workflow = new Workflow();
    workflow.setName("run-lifecycle");
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("echo", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "echo")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    // Submit queues the run synchronously: it parks claimable (ready/pending) - the state the
    // agent's getWorkflowQueue long-poll dispatches on.
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("WorkflowRun ready for agent pickup")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, run.getStatus());
              assertEquals(RunPhase.pending, run.getPhase());
            });

    // Agent starts the WorkflowRun; the async DAG walk queues the template task, which parks
    // ready/pending awaiting a handler (template tasks are not auto-executed).
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("template TaskRun ready for agent pickup")
        .untilAsserted(
            () -> {
              Optional<TaskRunEntity> taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId);
              assertTrue(taskRun.isPresent());
              assertEquals(RunStatus.ready, taskRun.get().getStatus());
              assertEquals(RunPhase.pending, taskRun.get().getPhase());
            });
    String echoTaskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow().getId();

    // Agent starts the TaskRun.
    taskRunService.start(echoTaskRunId, Optional.empty());
    awaitEngine("TaskRun running")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun = taskRunRepository.findById(echoTaskRunId).orElseThrow();
              assertEquals(RunStatus.running, taskRun.getStatus());
              assertEquals(RunPhase.running, taskRun.getPhase());
            });

    // Agent ends the TaskRun with a result; the async graph advance finishes the workflow.
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    RunResult result = new RunResult();
    result.setName("echoed");
    result.setValue("hello");
    endRequest.getResults().add(result);
    taskRunService.end(echoTaskRunId, Optional.of(endRequest));
    awaitEngine("WorkflowRun succeeded and completed")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.succeeded, run.getStatus());
              assertEquals(RunPhase.completed, run.getPhase());
            });

    // Agent finalizes on the completed phase.
    workflowRunService.finalize(wfRunId);
    awaitEngine("WorkflowRun finalized")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunPhase.finalized, run.getPhase());
              assertEquals(RunStatus.succeeded, run.getStatus());
            });

    // Exactly the 3 DAG steps, all terminal and succeeded, with the agent result recorded.
    List<TaskRunEntity> taskRuns = taskRunRepository.findByWorkflowRunRef(wfRunId);
    assertEquals(3, taskRuns.size(), "expected exactly start, echo and end TaskRuns");
    for (TaskRunEntity taskRun : taskRuns) {
      assertEquals(RunPhase.completed, taskRun.getPhase(), taskRun.getName());
      assertEquals(RunStatus.succeeded, taskRun.getStatus(), taskRun.getName());
    }
    assertTrue(
        taskRunRepository.findById(echoTaskRunId).orElseThrow().getResults().stream()
            .anyMatch(r -> "echoed".equals(r.getName())),
        "agent-provided result should be recorded on the TaskRun");
  }

  @Test
  void customTaskMaterialisesImageCommandArgumentsAndScriptFromParams() {
    String taskSlug = customTaskWithDeclaredSpecParams("custom-lifecycle-spec-params");

    Workflow workflow = new Workflow();
    workflow.setName("run-custom-lifecycle-spec-params");
    WorkflowTask work = workflowTask("work", TaskType.custom, taskSlug, "start");
    work.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("image", "alpine:3.19"),
                new RunParam("command", "echo hello\n echo world \n\n"),
                new RunParam("arguments", ".\n--flag"),
                new RunParam("shellScript", "echo 'hi'\necho done"))));
    workflow.setTasks(
        List.of(workflowTask("start", TaskType.start, null), work, workflowTask("end", TaskType.end, null, "work")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("custom TaskRun materialised")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).isPresent()));

    TaskRunEntity taskRun =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
    assertEquals("alpine:3.19", taskRun.getSpec().getImage());
    assertEquals(List.of("echo hello", "echo world"), taskRun.getSpec().getCommand());
    assertEquals(List.of(".", "--flag"), taskRun.getSpec().getArguments());
    assertEquals("echo 'hi'\necho done", taskRun.getSpec().getScript());
  }

  @Test
  void customTaskWithoutAnImageParamLeavesSpecImageNull() {
    String taskSlug = customTaskWithDeclaredSpecParams("custom-lifecycle-no-image");

    Workflow workflow = new Workflow();
    workflow.setName("run-custom-lifecycle-no-image");
    WorkflowTask work = workflowTask("work", TaskType.custom, taskSlug, "start");
    work.setParams(new LinkedList<>(List.of(new RunParam("command", "echo hi"))));
    workflow.setTasks(
        List.of(workflowTask("start", TaskType.start, null), work, workflowTask("end", TaskType.end, null, "work")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("custom TaskRun materialised")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).isPresent()));

    TaskRunEntity taskRun =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
    assertNull(taskRun.getSpec().getImage());
    assertEquals(List.of("echo hi"), taskRun.getSpec().getCommand());
  }

  @Test
  void templateTaskSpecIsUnaffectedByCustomParamMapping() {
    // A template task never runs applyCustomTaskSpecFromParams - its image/command come from the
    // catalogue Task Spec even when a node param happens to be named "image".
    Task template = new Task();
    template.setName("lifecycle-template-spec-unaffected");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    AbstractParam imageParam = new AbstractParam();
    imageParam.setName("image");
    imageParam.setType("text");
    template.getSpec().setParams(new LinkedList<>(List.of(imageParam)));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName("run-template-spec-unaffected");
    WorkflowTask work = workflowTask("work", TaskType.template, templateId, "start");
    work.setParams(new LinkedList<>(List.of(new RunParam("image", "should-not-apply:latest"))));
    workflow.setTasks(
        List.of(workflowTask("start", TaskType.start, null), work, workflowTask("end", TaskType.end, null, "work")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("template TaskRun materialised")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).isPresent()));

    TaskRunEntity taskRun =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
    assertEquals("busybox:latest", taskRun.getSpec().getImage());
  }

  @Test
  void taskWorkspacesAreCopiedOntoTheMaterialisedTaskRun() {
    Task template = new Task();
    template.setName("lifecycle-workspace-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    WorkflowTask echo = workflowTask("echo", TaskType.template, templateId, "start");
    TaskWorkspace taskWorkspace = new TaskWorkspace();
    taskWorkspace.setName("run-store");
    taskWorkspace.setType("workflowrun");
    taskWorkspace.setMountPath("/workspace/run");
    echo.setWorkspaces(new LinkedList<>(List.of(taskWorkspace)));

    Workflow workflow = new Workflow();
    workflow.setName("run-lifecycle-task-workspace");
    workflow.setTasks(
        List.of(workflowTask("start", TaskType.start, null), echo, workflowTask("end", TaskType.end, null, "echo")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    awaitEngine("echo TaskRun materialised")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).isPresent()));

    TaskRunEntity echoTaskRun =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow();
    assertEquals(1, echoTaskRun.getWorkspaces().size(), "the Task's own workspaces must be copied");
    TaskWorkspace copied = echoTaskRun.getWorkspaces().get(0);
    assertEquals("run-store", copied.getName());
    assertEquals("workflowrun", copied.getType());
    assertEquals("/workspace/run", copied.getMountPath());
  }

  // A run with Workspaces must be provisioned by the dispatcher before it starts - start=true
  // stays ready/pending rather than being started directly, and the run becomes claimable.
  @Test
  void aRunWithWorkspacesSubmittedWithStartTrueStaysReadyPendingForProvisioning() {
    String templateId = simpleTemplate("lifecycle-workspace-claim-provision");
    Workflow workflow = runnableWorkflowWithWorkspace("run-lifecycle-provision-claim", templateId);
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();

    awaitEngine("WorkflowRun with Workspaces parked for dispatcher provisioning")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, run.getStatus());
              assertEquals(RunPhase.pending, run.getPhase());
            });

    assertTrue(
        workflowRunStateHelper.findClaimableForProvision(50).stream()
            .anyMatch(r -> wfRunId.equals(r.getId())),
        "a ready/pending run with Workspaces must be claimable for provisioning");
  }

  // A run with no Workspaces has nothing to provision, so start=true starts it directly and it
  // never becomes a provisioning claimant.
  @Test
  void aRunWithoutWorkspacesSubmittedWithStartTrueStartsDirectlyAndIsNotClaimableForProvision() {
    String templateId = simpleTemplate("lifecycle-no-workspace-claim-start");
    Workflow workflow = runnableWorkflow("run-lifecycle-no-workspace-start", templateId);
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();

    awaitEngine("WorkflowRun without Workspaces started directly")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.running, run.getStatus());
              assertEquals(RunPhase.running, run.getPhase());
            });

    assertTrue(
        workflowRunStateHelper.findClaimableForProvision(50).stream()
            .noneMatch(r -> wfRunId.equals(r.getId())),
        "a run without Workspaces must never be claimable for provisioning");
  }

  // A run with no Workspaces parked with start=false (an explicit later PUT .../start) also has
  // nothing to provision, so it must not be picked up by the provisioning claim either.
  @Test
  void aRunParkedWithoutWorkspacesIsNotClaimableForProvision() {
    String templateId = simpleTemplate("lifecycle-no-workspace-claim-park");
    Workflow workflow = runnableWorkflow("run-lifecycle-no-workspace-park", templateId);
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();

    awaitEngine("WorkflowRun without Workspaces parked ready/pending")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, run.getStatus());
              assertEquals(RunPhase.pending, run.getPhase());
            });

    assertTrue(
        workflowRunStateHelper.findClaimableForProvision(50).stream()
            .noneMatch(r -> wfRunId.equals(r.getId())),
        "a parked run without Workspaces must never be claimable for provisioning");
  }

  private String simpleTemplate(String name) {
    Task template = new Task();
    template.setName(name);
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    return taskService.create(template).getId();
  }

  // AbstractEngineIntegrationTest.runnableWorkflow gives start -> work -> end; this just adds a
  // Workflow-level Workspace on top.
  private static Workflow runnableWorkflowWithWorkspace(String name, String taskSlug) {
    Workflow workflow = runnableWorkflow(name, taskSlug);
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setName("run-store");
    ws.setType("workflowrun");
    workflow.setWorkspaces(new LinkedList<>(List.of(ws)));
    return workflow;
  }

  /**
   * A global custom Task declaring the four spec-mapped params (image/command/arguments/
   * shellScript), mirroring the seeded run-custom-task catalogue entry. No catalogue Task Spec
   * image is set, so a submitted node's params are the only source of the TaskRun spec.
   */
  private String customTaskWithDeclaredSpecParams(String name) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.custom);
    AbstractParam image = new AbstractParam();
    image.setName("image");
    image.setType("text");
    AbstractParam command = new AbstractParam();
    command.setName("command");
    command.setType("textarea");
    AbstractParam arguments = new AbstractParam();
    arguments.setName("arguments");
    arguments.setType("textarea");
    AbstractParam shellScript = new AbstractParam();
    shellScript.setName("shellScript");
    shellScript.setType("texteditor::shell");
    task.getSpec().setParams(new LinkedList<>(List.of(image, command, arguments, shellScript)));
    return taskService.create(task).getId();
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
