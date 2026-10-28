package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTemplate;
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
 * A Workflow is created from a template by posting the template body, as the template routes read
 * it, to the workspace Workflow create route. Every seeded template does so, with its tasks' slug
 * forms carried through.
 */
class WorkflowTemplateCreateTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowTemplateService templateService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ObjectMapper mapper;

  @Test
  void everySeededTemplateCreatesAWorkflow() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("template-create-" + UUID.randomUUID().toString().substring(0, 8));
    request.setDisplayName(request.getName());
    String workspace = workspaceService.create(request).getName();

    List<WorkflowTemplate> templates =
        templateService
            .query(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
            .getContent();
    assertFalse(templates.isEmpty());

    for (WorkflowTemplate template : templates) {
      Workflow body =
          mapper.readValue(mapper.writeValueAsString(template), Workflow.class);
      Workflow created = workflowService.create(workspace, body);

      assertEquals(template.getName(), created.getName());
      assertEquals(taskRefs(template.getTasks()), taskRefs(created.getTasks()));
      assertEquals(
          taskRefs(template.getTasks()),
          taskRefs(templateService.get(template.getName(), Optional.empty(), true).getTasks()));
    }
  }

  private static Map<String, String> taskRefs(List<WorkflowTask> tasks) {
    return tasks.stream()
        .filter(t -> !t.getName().equals("start") && !t.getName().equals("end"))
        .collect(Collectors.toMap(WorkflowTask::getName, WorkflowTask::getTaskRef));
  }
}
