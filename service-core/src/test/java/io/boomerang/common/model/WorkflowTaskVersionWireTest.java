package io.boomerang.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A workflow node's {@code taskVersion} on the wire is a number or {@code "latest"}. A number pins
 * the node; {@code "latest"} is held as no version, which the engine reads as the latest; left out,
 * the save pins the current version. A read shows a node with no version as {@code "latest"}.
 */
class WorkflowTaskVersionWireTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final YAMLMapper YAML = YAMLMapper.builder().build();

  @Test
  void aNumberPinsTheNode() {
    WorkflowTask task = JSON.readValue("{\"taskRef\":\"build\",\"taskVersion\":4}", WorkflowTask.class);

    assertEquals(4, task.getTaskVersion());
    assertFalse(task.isPinOnSave());
    assertEquals(4, ((ObjectNode) JSON.valueToTree(task)).get("taskVersion").asInt());
  }

  @Test
  void latestIsHeldAsNoVersionAndReadBackAsLatest() {
    WorkflowTask task =
        JSON.readValue("{\"taskRef\":\"build\",\"taskVersion\":\"latest\"}", WorkflowTask.class);

    assertNull(task.getTaskVersion());
    assertFalse(task.isPinOnSave());
    assertEquals("latest", ((ObjectNode) JSON.valueToTree(task)).get("taskVersion").asString());
  }

  @Test
  void aNodeThatNamesNoVersionIsPinnedOnSave() {
    WorkflowTask task = JSON.readValue("{\"taskRef\":\"build\"}", WorkflowTask.class);

    assertNull(task.getTaskVersion());
    assertTrue(task.isPinOnSave());
    assertFalse(((ObjectNode) JSON.valueToTree(task)).has("taskVersion"));
  }

  @Test
  void aStoredNodeWithNoVersionReadsAsLatestAndStartAndEndShowNone() {
    WorkflowTask stored = new WorkflowTask();
    stored.setTaskRef("build");
    WorkflowTask start = new WorkflowTask();
    start.setName("start");

    assertEquals("latest", ((ObjectNode) JSON.valueToTree(stored)).get("taskVersion").asString());
    assertFalse(((ObjectNode) JSON.valueToTree(start)).has("taskVersion"));
  }

  @Test
  void anyOtherValueIsKeptForTheSaveToRefuse() {
    WorkflowTask task =
        JSON.readValue("{\"taskRef\":\"build\",\"taskVersion\":\"newest\"}", WorkflowTask.class);

    assertNull(task.getTaskVersion());
    assertFalse(task.isPinOnSave());
    assertEquals("newest", task.getInvalidTaskVersion());
  }

  @Test
  void yamlRoundTripsLatest() {
    WorkflowTask task =
        YAML.readValue("taskRef: build\ntaskVersion: latest\n", WorkflowTask.class);
    String written = YAML.writeValueAsString(task);

    assertNull(task.getTaskVersion());
    assertTrue(written.contains("taskVersion: \"latest\"") || written.contains("taskVersion: latest"));
    WorkflowTask again = YAML.readValue(written, WorkflowTask.class);
    assertNull(again.getTaskVersion());
    assertFalse(again.isPinOnSave());
  }

  @Test
  void neitherMarkerReachesTheWire() {
    ObjectNode node = (ObjectNode) JSON.valueToTree(new WorkflowTask());

    assertFalse(node.has("pinOnSave"));
    assertFalse(node.has("invalidTaskVersion"));
    assertFalse(node.has("versionOnWire"));
    assertFalse(node.has("requestVersion"));
  }
}
