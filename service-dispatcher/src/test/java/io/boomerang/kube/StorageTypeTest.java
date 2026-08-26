package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Lives here (not under lib-common/src/test/java) because lib-common's pom does not declare a
// test framework and this scoped change may not touch pom.xml; service-dispatcher already has one.
import io.boomerang.common.enums.StorageType;
import org.junit.jupiter.api.Test;

public class StorageTypeTest {

  @Test
  public void testFromLabelMatchesCaseInsensitively() {
    assertEquals(StorageType.workflow, StorageType.fromLabel("workflow").orElseThrow());
    assertEquals(StorageType.workflow, StorageType.fromLabel("WORKFLOW").orElseThrow());
    assertEquals(StorageType.workflowRun, StorageType.fromLabel("workflowrun").orElseThrow());
    assertEquals(StorageType.workflowRun, StorageType.fromLabel("WorkflowRun").orElseThrow());
  }

  @Test
  public void testFromLabelReturnsEmptyForUnknownOrNull() {
    assertTrue(StorageType.fromLabel("workfowRun").isEmpty());
    assertTrue(StorageType.fromLabel("bogus").isEmpty());
    assertTrue(StorageType.fromLabel(null).isEmpty());
  }
}
