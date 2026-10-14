package io.boomerang.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.model.WorkflowWorkspace;
import org.junit.jupiter.api.Test;

/**
 * A workspace size reaches Kubernetes as a Quantity, which reads a bare number as bytes. The engine
 * therefore writes the Gi shorthand authors and the v3 data use as an explicit quantity, and
 * leaves a size that already carries a unit alone.
 */
class StorageQuantityUtilTest {

  @Test
  void aBareNumberIsWrittenAsGi() {
    assertEquals("2Gi", StorageQuantityUtil.normalise("2"));
    assertEquals("2Gi", StorageQuantityUtil.normalise(" 2 "));
    assertEquals("2Gi", StorageQuantityUtil.normalise("2.0"));
    assertEquals("1.5Gi", StorageQuantityUtil.normalise("1.5"));
  }

  @Test
  void aQuantityWithAUnitIsKept() {
    assertEquals("500Mi", StorageQuantityUtil.normalise("500Mi"));
    assertEquals("2Gi", StorageQuantityUtil.normalise("2Gi"));
    assertEquals("1G", StorageQuantityUtil.normalise("1G"));
    assertNull(StorageQuantityUtil.normalise(null));
  }

  @Test
  void toGiReadsBothForms() {
    assertEquals(2d, StorageQuantityUtil.toGi("2"));
    assertEquals(2d, StorageQuantityUtil.toGi("2Gi"));
    assertEquals(0.5d, StorageQuantityUtil.toGi("512Mi"));
  }

  @Test
  void settleWritesTheQuantityAndTheDefaultMountPath() {
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("4");
    workspace.settle();
    assertEquals("4Gi", workspace.getSize());
    assertEquals(WorkflowWorkspace.DEFAULT_MOUNT_PATH, workspace.getMountPath());

    WorkflowWorkspace declared = new WorkflowWorkspace();
    declared.setSize("500Mi");
    declared.setMountPath("/workspace/run");
    declared.settle();
    assertEquals("500Mi", declared.getSize());
    assertEquals("/workspace/run", declared.getMountPath());

    WorkflowWorkspace blank = new WorkflowWorkspace();
    blank.settle();
    assertNull(blank.getSize());
    assertEquals(WorkflowWorkspace.DEFAULT_MOUNT_PATH, blank.getMountPath());
  }
}
