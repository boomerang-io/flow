package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * A starter is copied: a workflow is created from it by posting its body, as the workspace's
 * workflow routes read it, to the workspace Workflow create route with {@code type} set to {@code
 * workflow}. Every seeded starter does so, with its tasks' slug forms carried through, and the copy
 * is a plain workflow of the workspace.
 */
class WorkflowTemplateCreateTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ObjectMapper mapper;

  @Test
  void everySeededStarterCreatesAWorkflow() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("template-create-" + UUID.randomUUID().toString().substring(0, 8));
    request.setDisplayName(request.getName());
    String workspace = workspaceService.create(request).getName();

    List<Workflow> starters =
        workflowService
            .query(
                workspace,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(List.of(WorkflowType.starter.name())))
            .getContent();
    assertFalse(starters.isEmpty());

    for (Workflow starter : starters) {
      assertEquals(WorkflowType.starter, starter.getType());
      assertEquals(
          taskRefs(starter.getTasks()),
          taskRefs(
              workflowService.get(workspace, starter.getName(), Optional.empty(), true).getTasks()),
          "the workspace reads the starter by name before it has a copy");
      Workflow body = mapper.readValue(mapper.writeValueAsString(starter), Workflow.class);
      body.setType(WorkflowType.workflow);
      Workflow created = workflowService.create(workspace, body);

      assertEquals(starter.getName(), created.getName());
      assertNull(created.getTemplateRef(), "a copy is its own workflow");
      assertEquals(WorkflowType.workflow, created.getType());
      assertEquals(taskRefs(starter.getTasks()), taskRefs(created.getTasks()));
    }
  }

  private static Map<String, String> taskRefs(List<WorkflowTask> tasks) {
    return tasks.stream()
        .filter(t -> !t.getName().equals("start") && !t.getName().equals("end"))
        .collect(Collectors.toMap(WorkflowTask::getName, WorkflowTask::getTaskRef));
  }
}
