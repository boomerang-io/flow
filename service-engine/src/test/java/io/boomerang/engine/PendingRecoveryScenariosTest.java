package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Disabled placeholders for safety-net scenarios (gap-register.md section 3) whose mechanisms do
 * not exist yet. Implement and enable each as part of the epic that introduces the behaviour.
 * Deliberately not extending AbstractEngineIntegrationTest - no context boot for disabled stubs.
 */
class PendingRecoveryScenariosTest {

  // Claim-epoch fencing at start/end is implemented and covered by TaskClaimFencingTest.

  // Requires pauseRequestedAt (E3 schema, C6) with claim-query exclusion and resume-as-reconcile
  // (E4). Constraint H15: no PAUSED RunStatus - pause is an orthogonal flag, excluded at the query.
  @Disabled("Pause (pauseRequestedAt, C6) does not exist until E3 schema + E4 - gap-register scenario #8")
  @Test
  void pausedRunIsExcludedFromClaimUntilResumeReconciles() {
    fail("Implement with E4: paused runs excluded from claimable page; resume = clear flag + reconcile");
  }

  // Today only the stopgap guard exists (WorkflowService.delete refuses while runs are in
  // flight). The tombstonedAt field + watcher (CF-4) land in E4.
  @Disabled("Tombstone/watcher delete model (CF-4) does not exist until E4 - gap-register scenario #9")
  @Test
  void deletedWorkflowIsTombstonedCancelledAndPruned() {
    fail("Implement with E4: tombstonedAt set; watcher cancels in-flight; retention sweep prunes; orphan backstop");
  }

  // Outbox + idempotency keys land across E3 (indexes) and E4 (outbox, B7/B9/B11/B13); ingress
  // correlation in E9 (C10).
  @Disabled("Outbox/dedup/idempotency keys (B9/B13/C10) do not exist until E3/E4/E9 - gap-register scenario #10")
  @Test
  void duplicateEventsAndSubmissionsAreDeduplicated() {
    fail("Implement with E4/E9: duplicate delivery yields single append/end; outbox exactly-once; duplicate submit deduped");
  }
}
