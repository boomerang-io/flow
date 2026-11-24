package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTemplateRef;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A workflow as the list reads it is unmetered when it is a listed workflow of the system
 * workspace, a template or starter of the system workspace shown in another workspace's list, or a
 * link to a listed template; a workflow of another workspace with a listed name is not. The list is
 * exact names, trimmed, and the flag is never read from a request body.
 */
class UnmeteredWorkflowsFlagTest {

  private static final List<String> LISTED = List.of("index-rebuild", "nightly");

  @Test
  void aListedNameOutsideTheSystemWorkspaceIsMetered() {
    assertThat(UnmeteredWorkflows.unmetered(LISTED, "team", workflow("index-rebuild", null, null)))
        .isFalse();
    assertThat(
            UnmeteredWorkflows.unmetered(
                LISTED, "team", workflow("index-rebuild", WorkflowType.workflow, null)))
        .isFalse();
  }

  @Test
  void aListedSystemWorkflowIsUnmeteredAndAnotherIsNot() {
    assertThat(UnmeteredWorkflows.unmetered(LISTED, "system", workflow("index-rebuild", null, null)))
        .isTrue();
    assertThat(UnmeteredWorkflows.unmetered(LISTED, "system", workflow("reports", null, null)))
        .isFalse();
    assertThat(UnmeteredWorkflows.unmetered(LISTED, "System", workflow("index-rebuild", null, null)))
        .as("the system workspace's name, exactly")
        .isFalse();
  }

  @Test
  void aLinkInAnotherWorkspaceIsJudgedByItsTemplate() {
    assertThat(
            UnmeteredWorkflows.unmetered(LISTED, "team", workflow("rebuild", null, "index-rebuild")))
        .isTrue();
    assertThat(
            UnmeteredWorkflows.unmetered(LISTED, "team", workflow("index-rebuild", null, "reports")))
        .as("its own name does not matter")
        .isFalse();
  }

  // A template or starter in another workspace's list is the system workspace's own.
  @Test
  void aTemplateShownInAnotherWorkspaceIsJudgedByItsOwnWorkspace() {
    assertThat(
            UnmeteredWorkflows.unmetered(
                LISTED, "team", workflow("nightly", WorkflowType.template, null)))
        .isTrue();
    assertThat(
            UnmeteredWorkflows.unmetered(
                LISTED, "team", workflow("nightly", WorkflowType.starter, null)))
        .isTrue();
    assertThat(
            UnmeteredWorkflows.unmetered(
                LISTED, "team", workflow("reports", WorkflowType.template, null)))
        .isFalse();
  }

  @Test
  void anEmptyListMetersEverything() {
    assertThat(UnmeteredWorkflows.unmetered(List.of(), "system", workflow("nightly", null, null)))
        .isFalse();
    assertThat(UnmeteredWorkflows.parse("")).isEmpty();
    assertThat(UnmeteredWorkflows.parse(null)).isEmpty();
  }

  @Test
  void theListIsExactNamesSplitOnCommasAndTrimmed() {
    assertThat(UnmeteredWorkflows.parse(" index-rebuild , nightly,,Nightly "))
        .containsExactly("index-rebuild", "nightly", "Nightly");
  }

  @Test
  void theFlagIsNeverReadFromARequestBody() {
    JsonMapper mapper = JsonMapper.builder().build();

    Workflow read = mapper.readValue("{\"name\":\"x\",\"unmetered\":true}", Workflow.class);
    Workflow written = workflow("x", null, null);
    written.setUnmetered(true);

    assertThat(read.getUnmetered()).isNull();
    assertThat(mapper.writeValueAsString(written)).contains("\"unmetered\":true");
  }

  private static Workflow workflow(String name, WorkflowType type, String templateName) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setType(type);
    if (templateName != null) {
      WorkflowTemplateRef ref = new WorkflowTemplateRef();
      ref.setName(templateName);
      workflow.setTemplateRef(ref);
    }
    return workflow;
  }
}
