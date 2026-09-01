package io.boomerang.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Characterisation of what the PUBLIC {@code /api/v2} run models actually put on the wire.
 *
 * <p>{@link WorkflowRun} and {@link TaskRun} are the SAME classes returned by both the public v2
 * surface (e.g. {@code WorkspaceWorkflowRunControllerV2.get/query/start/cancel}) and the
 * worker-facing {@code /api/v1/dispatcher} surface ({@code DispatcherControllerV1}). There is no
 * bespoke wire model separating the two, so every field on these classes is visible to BOTH
 * audiences. That makes this test the contract guard for the v2 surface.
 *
 * <p>Two distinct invariants are covered:
 *
 * <ul>
 *   <li><b>Execution state must never leak</b> (E7-2, holds today) — {@code claim}, {@code
 *       timeoutAt}, {@code retry}, {@code waitUntil}, {@code pauseRequestedAt}, {@code agentRef}
 *       and {@code dispatcherRef} live on the ENTITIES only. The models are standalone POJOs
 *       precisely so these cannot escape.
 *   <li><b>{@code phase} is currently serialised</b> — this DOCUMENTS a known deviation from the
 *       stated architecture invariant ("status is the only external-facing field; phase is
 *       internal orchestration state and is never exposed in public API responses"). {@code
 *       phase} is legitimate on the dispatcher wire, but because one class serves both surfaces it
 *       also reaches {@code /api/v2}. See {@link #phaseIsStillSerialisedOnThePublicModels()}.
 * </ul>
 */
class PublicRunModelSerialisationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Internal execution-state fields that live on the entities and MUST NOT appear on either public
   * model. A regression here is a genuine contract break, not a judgement call.
   *
   * <p>{@code statusReason} is deliberately NOT in this set: like {@code statusMessage}, it is a
   * public field (the closed-set typed cause paired with the human-readable message), not
   * execution state - so it is expected to serialise on both models.
   */
  private static final Set<String> FORBIDDEN_EXECUTION_STATE =
      Set.of(
          "claim",
          "timeoutAt",
          "retry",
          "retryAfter",
          "waitUntil",
          "pauseRequestedAt",
          "agentRef",
          "dispatcherRef",
          "dependencies",
          "preApproved",
          "decisionValue");

  private static ObjectNode serialise(Object model) {
    return (ObjectNode) MAPPER.valueToTree(model);
  }

  @Test
  void workflowRunExposesNoExecutionState() {
    ObjectNode json = serialise(new WorkflowRun());

    assertThat(json.propertyNames())
        .as("WorkflowRun must not serialise any internal execution-state field")
        .doesNotContainAnyElementsOf(FORBIDDEN_EXECUTION_STATE);
  }

  @Test
  void taskRunExposesNoExecutionState() {
    ObjectNode json = serialise(new TaskRun());

    assertThat(json.propertyNames())
        .as("TaskRun must not serialise any internal execution-state field")
        .doesNotContainAnyElementsOf(FORBIDDEN_EXECUTION_STATE);
  }

  @Test
  void workflowRunExposesPauseOnlyAsTheDerivedBoolean() {
    ObjectNode json = serialise(new WorkflowRun());

    assertThat(json.has("paused"))
        .as("pause is exposed as a derived boolean, never as the pauseRequestedAt timestamp")
        .isTrue();
    assertThat(json.has("pauseRequestedAt")).isFalse();
  }

  @Test
  void bothModelsExposeStatus() {
    assertThat(serialise(new WorkflowRun()).has("status")).isTrue();
    // TaskRun is @JsonInclude(NON_NULL) and status defaults to null, so assert the property is
    // declared rather than that it is emitted on an empty instance.
    assertThat(fieldNamesOf(TaskRun.class)).contains("status");
  }

  /**
   * TRIPWIRE — asserts the CURRENT (violating) behaviour so the deviation is visible in the test
   * suite rather than only in a specification document.
   *
   * <p>The architecture invariant says {@code phase} must never appear in a public {@code /api/v2}
   * response. It does, because {@link WorkflowRun}/{@link TaskRun} are shared with the dispatcher
   * wire, which legitimately needs {@code phase} to dispatch on. Fixing this requires either a
   * dispatcher-specific wire model or a {@code @JsonView}/mixin split — a design decision, not a
   * drive-by change.
   *
   * <p><b>When {@code phase} is removed from the public surface, invert these assertions.</b>
   */
  @Test
  void phaseIsStillSerialisedOnThePublicModels() {
    assertThat(serialise(new WorkflowRun()).has("phase"))
        .as(
            "KNOWN DEVIATION: phase is serialised on the public v2 WorkflowRun response. "
                + "Invert this assertion once the public/dispatcher model split lands.")
        .isTrue();

    assertThat(fieldNamesOf(TaskRun.class))
        .as(
            "KNOWN DEVIATION: phase is declared on the public v2 TaskRun response. "
                + "Invert this assertion once the public/dispatcher model split lands.")
        .contains("phase");
  }

  private static Set<String> fieldNamesOf(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
        .map(java.lang.reflect.Field::getName)
        .collect(java.util.stream.Collectors.toSet());
  }
}
