package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #359: pins the exact stamping site, {@code WorkflowService.submit(String,
 * WorkflowSubmitRequest, boolean, String initiatedByRef)} (WorkflowService.java, the trigger-set
 * block around line 1817) - the internal-only overload {@code ScheduleJob} calls with the firing
 * Schedule's id. Ruled design (Option A): reuse the existing {@code initiatedByRef} field, the
 * same one the retry path stamps (WorkflowRunService.java:930-936) - no new field.
 *
 * <p>{@link WorkflowSubmitRequest} deliberately has no {@code initiatedByRef} setter - it is a
 * public API request model and lineage must not be spoofable by API callers. This test drives the
 * internal overload directly, exactly as {@code ScheduleJob} does, never via the request.
 */
class ScheduleLineageSubmitTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;

  @Test
  void aScheduleFiredSubmitStampsTheScheduleIdAsInitiatedByRef() {
    String workflowId = createLinearWorkflow("schedule-lineage-stamp");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.schedule);

    WorkflowRun run = workflowService.submit(workflowId, request, false, "schedule-abc-123");

    assertEquals("schedule-abc-123", run.getInitiatedByRef());
    assertEquals(TriggerEnum.schedule.getTrigger(), run.getTrigger());
  }

  @Test
  void aNonScheduleSubmitLeavesInitiatedByRefNull() {
    String workflowId = createLinearWorkflow("schedule-lineage-manual");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);

    // The 3-arg overload every other caller uses - equivalent to initiatedByRef=null.
    WorkflowRun run = workflowService.submit(workflowId, request, false);

    assertNull(run.getInitiatedByRef());
    assertEquals(TriggerEnum.manual.getTrigger(), run.getTrigger());
  }

  private String createLinearWorkflow(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("a", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "a")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (dependsOn.length > 0) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn[0]);
      task.setDependencies(new java.util.LinkedList<>(List.of(dependency)));
    }
    return task;
  }
}
