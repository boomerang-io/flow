package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import org.junit.jupiter.api.Test;

class TraceparentTest {

  private static TaskRun taskRun(String workflowRunRef, String id) {
    TaskRun task = new TaskRun();
    task.setWorkflowRunRef(workflowRunRef);
    task.setId(id);
    return task;
  }

  @Test
  void theTraceIsTheRunAndTheParentIsTheTaskRun() {
    TaskRun task = taskRun("6AD1BFA3CFEFA66E88D5E24A", "6ad1bfa6cfefa66e88d5e2e0");

    assertThat(task.traceparent())
        .isEqualTo("00-000000006ad1bfa3cfefa66e88d5e24a-cfefa66e88d5e2e0-01");
  }

  @Test
  void twoTasksOfOneRunShareTheTraceId() {
    String first = taskRun("6ad1bfa3cfefa66e88d5e24a", "6ad1bfa6cfefa66e88d5e2e0").traceparent();
    String second = taskRun("6ad1bfa3cfefa66e88d5e24a", "6ad1bfa6cfefa66e88d5e2e1").traceparent();

    assertThat(first.substring(0, 36)).isEqualTo(second.substring(0, 36));
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void anIdThatIsNotAnObjectIdHasNoTraceparent() {
    assertThat(taskRun("run-1", "6ad1bfa6cfefa66e88d5e2e0").traceparent()).isNull();
    assertThat(taskRun("6ad1bfa3cfefa66e88d5e24a", null).traceparent()).isNull();
  }

  @Test
  void theContextHandsOutTheTaskRunsTraceparent() {
    TaskRun task = taskRun("6ad1bfa3cfefa66e88d5e24a", "6ad1bfa6cfefa66e88d5e2e0");

    assertThat(new TaskContext(task, "d-1", null).traceparent()).isEqualTo(task.traceparent());
  }
}
