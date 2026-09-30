package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunInsightSummary.Day;
import io.boomerang.common.model.WorkflowRunInsightSummary.Totals;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The statistics are pure functions of run records; this pins their definitions. */
class WorkflowRunInsightServiceTest {

  private static final Instant DAY_ONE = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  void percentileIsNearestRankAndZeroOverNothing() {
    List<Long> values = List.of(50L, 10L, 40L, 20L, 30L);
    assertThat(WorkflowRunInsightService.percentile(values, 0.5)).isEqualTo(30L);
    assertThat(WorkflowRunInsightService.percentile(values, 0.95)).isEqualTo(50L);
    assertThat(WorkflowRunInsightService.percentile(values, 0.05)).isEqualTo(10L);
    assertThat(WorkflowRunInsightService.percentile(List.of(), 0.5)).isZero();
  }

  @Test
  void successRateIgnoresCancelledAndUnfinishedRuns() {
    Totals totals =
        WorkflowRunInsightService.totals(
            List.of(
                run(RunStatus.succeeded, RunPhase.completed, 0, 1_000),
                run(RunStatus.succeeded, RunPhase.completed, 1, 3_000),
                run(RunStatus.failed, RunPhase.completed, 2, 500),
                run(RunStatus.timedout, RunPhase.completed, 3, 60_000),
                run(RunStatus.cancelled, RunPhase.completed, 4, 200),
                run(RunStatus.running, RunPhase.running, 5, 0)));

    assertThat(totals.getRuns()).isEqualTo(6);
    assertThat(totals.getSucceeded()).isEqualTo(2);
    assertThat(totals.getFailed()).isEqualTo(1);
    assertThat(totals.getTimedOut()).isEqualTo(1);
    assertThat(totals.getCancelled()).isEqualTo(1);
    assertThat(totals.getInFlight()).isEqualTo(1);
    // 2 succeeded of the 4 that finished with an outcome.
    assertThat(totals.getSuccessRate()).isEqualTo(0.5);
    // Duration percentiles read succeeded runs only; the maximum reads every completed run.
    assertThat(totals.getP50Duration()).isEqualTo(1_000);
    assertThat(totals.getP95Duration()).isEqualTo(3_000);
    assertThat(totals.getMaxDuration()).isEqualTo(60_000);
    assertThat(totals.getByTrigger()).containsEntry("manual", 6L);
  }

  @Test
  void successRateIsNullWhenNothingFinishedWithAnOutcome() {
    Totals totals =
        WorkflowRunInsightService.totals(
            List.of(run(RunStatus.cancelled, RunPhase.completed, 0, 0)));
    assertThat(totals.getSuccessRate()).isNull();
    assertThat(WorkflowRunInsightService.totals(List.of()).getSuccessRate()).isNull();
  }

  @Test
  void queueWaitIsStartMinusCreationAndNeverNegative() {
    WorkflowRunEntity late = run(RunStatus.succeeded, RunPhase.completed, 0, 1);
    late.setStartTime(new Date(late.getCreationDate().getTime() + 4_000));
    WorkflowRunEntity early = run(RunStatus.succeeded, RunPhase.completed, 0, 1);
    early.setStartTime(new Date(early.getCreationDate().getTime() - 4_000));
    WorkflowRunEntity unstarted = run(RunStatus.ready, RunPhase.pending, 0, 0);
    unstarted.setStartTime(null);

    assertThat(WorkflowRunInsightService.queueWaits(List.of(late, early, unstarted)))
        .containsExactly(4_000L, 0L);
  }

  @Test
  void dailyCoversEveryDayOfThePeriodAndBucketsCompletedRunsByOutcome() {
    Date from = Date.from(DAY_ONE);
    Date to = Date.from(DAY_ONE.plusSeconds(3 * 86_400));
    List<Day> days =
        WorkflowRunInsightService.daily(
            List.of(
                run(RunStatus.succeeded, RunPhase.completed, 0, 1),
                run(RunStatus.failed, RunPhase.completed, 0, 1),
                run(RunStatus.running, RunPhase.running, 0, 0),
                run(RunStatus.timedout, RunPhase.completed, 2, 1),
                // Outside the period: not counted, not a day of its own.
                run(RunStatus.succeeded, RunPhase.completed, 9, 1)),
            from,
            to);

    assertThat(days).extracting(Day::getDate).containsExactly("2026-09-01", "2026-09-02", "2026-09-03");
    assertThat(days.get(0).getSucceeded()).isEqualTo(1);
    assertThat(days.get(0).getFailed()).isEqualTo(1);
    assertThat(days.get(0).getOther()).isZero();
    assertThat(days.get(1).getSucceeded()).isZero();
    assertThat(days.get(2).getTimedOut()).isEqualTo(1);
  }

  private static WorkflowRunEntity run(RunStatus status, RunPhase phase, int dayOffset, long duration) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setStatus(status);
    run.setPhase(phase);
    run.setCreationDate(Date.from(DAY_ONE.plusSeconds(dayOffset * 86_400L + 3_600)));
    run.setStartTime(run.getCreationDate());
    run.setDuration(duration);
    run.setTrigger("manual");
    return run;
  }
}
