package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A workflow body carrying a top-level field the model does not have is saved without it, and the
 * field is never echoed back: the save response and every read show exactly what was stored.
 */
class WorkflowUnknownFieldTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "unknown-field-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void anUnknownTopLevelFieldIsIgnoredOnCreateAndNotReturned() {
    String workspace = createWorkspace("unknown-field-create");
    Workflow request =
        withUnknownFields(runnableWorkflow("unknown-field-create-" + System.nanoTime(), TASK_SLUG));

    Workflow created = workflowService.create(workspace, request);

    assertNoUnknownFields(objectMapper.valueToTree(created));
    assertNull(created.getWorkspace(), "the old list is not read as a workspace");
    assertNoUnknownFields(
        objectMapper.valueToTree(
            workflowService.get(workspace, created.getName(), Optional.empty(), true)));
  }

  @Test
  void anUnknownTopLevelFieldIsIgnoredOnApplyAndADeclaredWorkspaceStillRoundTrips() {
    String workspace = createWorkspace("unknown-field-apply");
    Workflow created =
        workflowService.create(
            workspace, runnableWorkflow("unknown-field-apply-" + System.nanoTime(), TASK_SLUG));

    Workflow update =
        workflowService.get(workspace, created.getName(), Optional.empty(), true);
    WorkflowWorkspace declared = new WorkflowWorkspace();
    declared.setSize("1Gi");
    declared.setMountPath("/workspace/run");
    update.setWorkspace(declared);
    Workflow applied = workflowService.apply(workspace, withUnknownFields(update), false);

    assertNoUnknownFields(objectMapper.valueToTree(applied));
    Workflow read = workflowService.get(workspace, created.getName(), Optional.empty(), true);
    assertNoUnknownFields(objectMapper.valueToTree(read));
    assertEquals("1Gi", read.getWorkspace().getSize());
    assertEquals("/workspace/run", read.getWorkspace().getMountPath());
  }

  // The body a client from an earlier beta sends: the retired workspaces list plus a stray key,
  // read through the application's own JSON mapper as a request body is.
  private Workflow withUnknownFields(Workflow workflow) {
    ObjectNode body = objectMapper.valueToTree(workflow);
    body.set(
        "workspaces",
        objectMapper.valueToTree(List.of(Map.of("name", "run", "type", "workflowrun"))));
    body.put("foo", "bar");
    return objectMapper.treeToValue(body, Workflow.class);
  }

  private static void assertNoUnknownFields(JsonNode json) {
    assertFalse(json.has("workspaces"), "workspaces is not echoed back");
    assertFalse(json.has("foo"), "an unknown field is not echoed back");
    assertFalse(json.has("unknownFields"), "no catch-all is serialised");
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }
}
