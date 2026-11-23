package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import org.junit.jupiter.api.Test;

/**
 * A refused submit is summarised as its code and a short plain cause, never the counts or the
 * message behind it; a failure that carries no code is named by its exception class alone.
 */
class RefusalSummaryTest {

  @Test
  void aQuotaRefusalNamesTheQuotaInPlainWordsWithoutItsCounts() {
    assertEquals(
        "QUOTA_EXCEEDED: maximum queued runs",
        summary(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, QuotaService.QUEUED_RUNS, 20, 20)));
    assertEquals(
        "QUOTA_EXCEEDED: maximum runs this month",
        summary(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, QuotaService.MONTHLY_RUNS, 100, 100)));
    assertEquals(
        "QUOTA_EXCEEDED: requested workspace size",
        summary(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, QuotaService.WORKSPACE_SIZE, "9Gi", 2)));
  }

  @Test
  void aWorkflowThatCannotRunIsNamedByItsCodeAndCause() {
    assertEquals(
        "WORKFLOWRUN_TRIGGER_DISABLED: trigger disabled",
        summary(new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED)));
    assertEquals(
        "WORKFLOW_NOT_ACTIVE: workflow not active",
        summary(new BoomerangException(BoomerangError.WORKFLOW_NOT_ACTIVE)));
  }

  @Test
  void aCodeWithNoCauseKeptForItStandsAlone() {
    assertEquals(
        "WORKFLOW_INVALID_REFERENCE",
        summary(new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF)));
    assertEquals(
        "QUOTA_EXCEEDED",
        summary(new BoomerangException(BoomerangError.QUOTA_EXCEEDED, "Number of Workflows", 10, 10)));
  }

  @Test
  void aFailureWithoutACodeIsItsExceptionClassAndNothingOfItsMessage() {
    assertEquals(
        "IllegalStateException",
        summary(new IllegalStateException("connection to db-internal:27017 refused")));
  }

  private static String summary(RuntimeException refusal) {
    return WorkflowService.refusalSummary(refusal);
  }
}
