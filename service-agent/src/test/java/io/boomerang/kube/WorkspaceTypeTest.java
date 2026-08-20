package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Lives here (not under lib-common/src/test/java) because lib-common's pom does not declare a
// test framework and this scoped change may not touch pom.xml; service-agent already has one.
import io.boomerang.common.enums.WorkspaceType;
import org.junit.jupiter.api.Test;

public class WorkspaceTypeTest {

  @Test
  public void testFromLabelMatchesCaseInsensitively() {
    assertEquals(WorkspaceType.workflow, WorkspaceType.fromLabel("workflow").orElseThrow());
    assertEquals(WorkspaceType.workflow, WorkspaceType.fromLabel("WORKFLOW").orElseThrow());
    assertEquals(WorkspaceType.workflowRun, WorkspaceType.fromLabel("workflowrun").orElseThrow());
    assertEquals(WorkspaceType.workflowRun, WorkspaceType.fromLabel("WorkflowRun").orElseThrow());
  }

  @Test
  public void testFromLabelReturnsEmptyForUnknownOrNull() {
    assertTrue(WorkspaceType.fromLabel("workfowRun").isEmpty());
    assertTrue(WorkspaceType.fromLabel("bogus").isEmpty());
    assertTrue(WorkspaceType.fromLabel(null).isEmpty());
  }
}
