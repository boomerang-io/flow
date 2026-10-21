package io.boomerang.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowTaskForeach;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Characterisation of what the PUBLIC {@code /api/v2} run models actually put on the wire.
 *
 * <p>{@link WorkflowRun} and {@link TaskRun} are what the public v2 surface returns (e.g. {@code
 * WorkspaceWorkflowRunControllerV2.get/query/start/cancel}). The worker-facing {@code
 * /dispatcher/v1} surface has its own models ({@code DispatchWorkflowRun}, {@code
 * DispatchTaskRun}), pinned by {@code contracts/dispatcher-v1.yaml}. This test is the contract
 * guard for the v2 surface.
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
 *       phase} is legitimate on the dispatcher wire, which now carries it on its own models; on
 *       {@code /api/v2} the webapp still reads it. See {@link
 *       #phaseIsStillSerialisedOnThePublicModels()}.
 * </ul>
 */
@Tag("smoke")
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
          "waitingSince",
          "agentRef",
          "dispatcherRef",
          "dependencies",
          "preApprovedStatus",
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

  /**
   * An item of a foreach task is an ordinary claimed, leased, timed TaskRun: its execution state
   * stays on the entity, while its parent and position are public. The parent's foreach setting,
   * which holds the resolved item array, stays on the entity too.
   */
  @Test
  void aForeachItemExposesItsParentAndIndexButNoExecutionState() {
    TaskRunEntity item = new TaskRunEntity();
    item.setName("locate[2]");
    item.setParentRef("parent-id");
    item.setIndex(2);
    RunClaim claim = new RunClaim();
    claim.setBy("dispatcher");
    claim.setSeq(1L);
    item.setClaim(claim);
    item.setTimeoutAt(new Date());
    item.setWaitUntil(new Date());
    item.setForeach(new WorkflowTaskForeach());

    ObjectNode json = serialise(new TaskRun(item));

    assertThat(json.get("parentRef").asString()).isEqualTo("parent-id");
    assertThat(json.get("index").asInt()).isEqualTo(2);
    assertThat(json.propertyNames())
        .doesNotContainAnyElementsOf(FORBIDDEN_EXECUTION_STATE)
        .doesNotContain("foreach");
  }

  /**
   * A task a dispatcher has claimed shows its attempt - 1, plus one per requeue - derived from
   * the retry state, which stays on the entity. A task no dispatcher claimed has no attempt.
   */
  @Test
  void aClaimedTaskExposesItsAttemptButNotItsRetryState() {
    TaskRunEntity requeued = new TaskRunEntity();
    RunClaim claim = new RunClaim();
    claim.setSeq(2L);
    requeued.setClaim(claim);
    RunRetry retry = new RunRetry();
    retry.setCount(1);
    requeued.setRetry(retry);

    ObjectNode json = serialise(new TaskRun(requeued));

    assertThat(json.get("attempt").asInt()).isEqualTo(2);
    assertThat(json.propertyNames()).doesNotContainAnyElementsOf(FORBIDDEN_EXECUTION_STATE);
    assertThat(serialise(new TaskRun(new TaskRunEntity())).has("attempt")).isFalse();
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
   * response. It does: the dispatcher no longer needs it here, as its wire has its own models, but
   * the webapp reads {@code TaskRun.phase} and the list routes filter on it. The one position
   * {@code status} cannot express is {@code queued} — every other phase pairs with a status the
   * caller already sees. Removing it is a public API decision, not a drive-by change.
   *
   * <p><b>When {@code phase} is removed from the public surface, invert these assertions.</b>
   */
  @Test
  void phaseIsStillSerialisedOnThePublicModels() {
    assertThat(serialise(new WorkflowRun()).has("phase"))
        .as(
            "KNOWN DEVIATION: phase is serialised on the public v2 WorkflowRun response. "
                + "Invert this assertion once phase leaves the public models.")
        .isTrue();

    assertThat(fieldNamesOf(TaskRun.class))
        .as(
            "KNOWN DEVIATION: phase is declared on the public v2 TaskRun response. "
                + "Invert this assertion once phase leaves the public models.")
        .contains("phase");
  }

  /**
   * The closed phase set. A run ends at {@code completed}: releasing its storage is the
   * dispatcher's reconciliation against the cluster, not a further phase, so nothing follows the
   * terminal one. Adding a member here changes what every consumer of the shared models can see.
   */
  @Test
  void theRunPhaseSetIsClosedAndEndsAtCompleted() {
    assertThat(RunPhase.values())
        .as("RunPhase is a closed set shared with the dispatcher and the frontend")
        .containsExactly(RunPhase.queued, RunPhase.pending, RunPhase.running, RunPhase.completed);
  }

  private static Set<String> fieldNamesOf(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
        .map(java.lang.reflect.Field::getName)
        .collect(java.util.stream.Collectors.toSet());
  }
}
