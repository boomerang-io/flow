package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * boomerang-io/flow#250: in v4, webhook-triggered runs lost their workspace PV while manual
 * runs mounted it. v5 has a single submit path (WorkflowService.internalSubmit) that copies
 * the revision's workspaces onto the WorkflowRun identically for every trigger. This pins
 * that a manual and a webhook submit of the same workflow produce runs with identical
 * workspace sets.
 */
class TriggerAgnosticWorkspacesTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;

  @Test
  void webhookAndManualSubmitsCarryIdenticalWorkspaces() {
    Task template = new Task();
    template.setName("trigger-ws-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName("trigger-agnostic-workspaces");
    workflow.getTriggers().getWebhook().setEnabled(true);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null, null),
                task("work", TaskType.template, templateId, "start"),
                task("end", TaskType.end, null, "work"))));
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setName("run-store");
    ws.setType("workflowrun");
    workflow.setWorkspaces(new LinkedList<>(List.of(ws)));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    WorkflowSubmitRequest manual = new WorkflowSubmitRequest();
    manual.setTrigger(TriggerEnum.manual);
    String manualRunId = workflowService.submit(workflowId, manual, false).getId();

    WorkflowSubmitRequest webhook = new WorkflowSubmitRequest();
    webhook.setTrigger(TriggerEnum.webhook);
    String webhookRunId = workflowService.submit(workflowId, webhook, false).getId();

    WorkflowRunEntity manualRun = workflowRunRepository.findById(manualRunId).orElseThrow();
    WorkflowRunEntity webhookRun = workflowRunRepository.findById(webhookRunId).orElseThrow();

    assertEquals(1, manualRun.getWorkspaces().size());
    assertEquals(
        manualRun.getWorkspaces(),
        webhookRun.getWorkspaces(),
        "the webhook-triggered run must carry exactly the workspaces the manual run does");
  }

  private static WorkflowTask task(String name, TaskType type, String taskRef, String dependsOn) {
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
}
