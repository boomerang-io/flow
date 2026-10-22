package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.flow.sdk.model.RunParam;
import io.boomerang.flow.sdk.model.TaskEnvVar;
import io.boomerang.flow.sdk.model.TaskRun;
import io.boomerang.flow.sdk.model.TaskRunSpec;
import io.boomerang.flow.sdk.model.WorkflowRun;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The run models carry what the public API answers and nothing of the engine's execution state,
 * and never print a value that may be a secret.
 */
class PublicModelSerialisationTest {

  // The engine's execution-state fields, which live on its entities and never reach the API.
  private static final Set<String> EXECUTION_STATE =
      Set.of(
          "claim",
          "timeoutAt",
          "retry",
          "retryAfter",
          "waitUntil",
          "pauseRequestedAt",
          "waitingSince",
          "agentRef",
          "dispatcherRef",
          "dependencies",
          "preApproved",
          "preApprovedStatus",
          "decisionValue",
          "foreach");

  @Test
  void theRunModelsDeclareNoExecutionStateField() {
    assertThat(ApiContractTest.properties(WorkflowRun.class).keySet())
        .doesNotContainAnyElementsOf(EXECUTION_STATE)
        .contains("status", "phase", "paused");
    assertThat(ApiContractTest.properties(TaskRun.class).keySet())
        .doesNotContainAnyElementsOf(EXECUTION_STATE)
        .contains("status", "phase", "attempt");
  }

  @Test
  void anExecutionStateFieldOnTheWireIsDropped() {
    String json =
        "{\"id\":\"r1\",\"status\":\"running\",\"claim\":{\"by\":\"d-1\"},"
            + "\"pauseRequestedAt\":\"2026-10-01T10:00:00Z\",\"paused\":true}";

    WorkflowRun run = WireFormat.JSON.readValue(json, WorkflowRun.class);
    JsonNode written = WireFormat.JSON.valueToTree(run);

    assertThat(run.paused()).isTrue();
    assertThat(written.propertyNames()).doesNotContainAnyElementsOf(EXECUTION_STATE);
  }

  @Test
  void nothingThatMayBeASecretIsPrinted() {
    RunParam secret = RunParam.secret("apiKey", "hunter2");
    TaskRunSpec spec =
        new TaskRunSpec(
            List.of("--key=hunter2"),
            List.of("sh"),
            List.of(new TaskEnvVar("KEY", "hunter2")),
            "img:1",
            "echo hunter2",
            null,
            null,
            null,
            null);
    TaskRun task =
        new TaskRun(
            "t1", null, "review", null, null, null, null, null, null, List.of(secret), null, null,
            spec, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null);
    WorkflowRun run =
        new WorkflowRun(
            "r1", null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, List.of(secret), null, null,
            List.of(task));

    assertThat(List.of(secret, spec, spec.envs().getFirst(), task, run).toString())
        .doesNotContain("hunter2");
  }
}
