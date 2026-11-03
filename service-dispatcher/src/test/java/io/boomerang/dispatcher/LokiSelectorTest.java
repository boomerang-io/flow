package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Loki is asked for a task's log by the labels the dispatcher sets on the task's pod, as a log
 * shipper's labelmap names them, and the task container of either executor.
 */
class LokiSelectorTest {

  @Test
  void selectsTheTasksPodLabelsAndEitherExecutorsTaskContainer() {
    assertThat(LogService.lokiSelector("wf-1", "tr-1"))
        .isEqualTo(
            "{boomerang_io_taskrun_ref=\"tr-1\",boomerang_io_workflow_ref=\"wf-1\",container=~\"task|step-task\"}");
  }
}
