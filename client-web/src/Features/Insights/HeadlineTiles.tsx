import React from "react";
import { Tile } from "@carbon/react";
import { ArrowDown, ArrowUp } from "@carbon/react/icons";
import cx from "classnames";
import { InsightsSummary } from "Types";
import { Delta, formatPercent, formatSucceededDuration, pointsDelta, relativeDelta, triggerMix } from "./format";
import { formatDuration } from "Utils/dateHelper";
import styles from "./Insights.module.scss";

interface HeadlineTilesProps {
  summary: InsightsSummary;
  /** When off, the previous-period comparison is hidden but the numbers stay. */
  compare: boolean;
}

interface TileProps {
  label: string;
  value: string;
  delta: Delta | null;
  /** "vs previous 90 days (79)" */
  comparedWith: string;
  detail: string;
  compare: boolean;
  testId: string;
}

function HeadlineTile({ label, value, delta, comparedWith, detail, compare, testId }: TileProps) {
  return (
    <Tile className={styles.headlineTile} data-testid={testId}>
      <span className={styles.headlineLabel}>{label}</span>
      <span className={styles.headlineValue}>{value}</span>
      {compare ? (
        <span className={cx(styles.headlineDelta, delta ? styles[`tone-${delta.tone}`] : styles["tone-neutral"])}>
          {delta ? (
            <>
              {delta.direction === "up" ? (
                <ArrowUp size={12} aria-label="up" />
              ) : delta.direction === "down" ? (
                <ArrowDown size={12} aria-label="down" />
              ) : null}
              {delta.direction === "flat" ? "No change" : delta.text} {comparedWith}
            </>
          ) : (
            "No previous period to compare with"
          )}
        </span>
      ) : null}
      <span className={styles.headlineDetail}>{detail}</span>
    </Tile>
  );
}

/** The four numbers the page leads with, each carrying its previous-period comparison. */
export default function HeadlineTiles({ summary, compare }: HeadlineTilesProps) {
  const { totals, previous } = summary;
  const periodDays = Math.max(1, Math.round((new Date(summary.to).getTime() - new Date(summary.from).getTime()) / 86_400_000));
  const previousLabel = `vs previous ${periodDays} days`;
  // Percentiles read succeeded runs; with none in a period there is nothing to time or compare.
  const timed = totals.succeeded > 0 && previous.succeeded > 0;
  const slowest = summary.workflows.reduce<InsightsSummary["workflows"][number] | null>(
    (slowestSoFar, row) => (slowestSoFar === null || row.maxDuration > slowestSoFar.maxDuration ? row : slowestSoFar),
    null,
  );

  return (
    <div className={styles.headline} data-testid="insights-headline">
      <HeadlineTile
        label="Runs"
        value={String(totals.runs)}
        delta={relativeDelta(totals.runs, previous.runs, null)}
        comparedWith={`${previousLabel} (${previous.runs})`}
        detail={triggerMix(totals.byTrigger) || "No runs in this period"}
        compare={compare}
        testId="insights-tile-runs"
      />
      <HeadlineTile
        label="Success rate"
        value={formatPercent(totals.successRate)}
        delta={pointsDelta(totals.successRate, previous.successRate)}
        comparedWith={`${previousLabel} (${formatPercent(previous.successRate)})`}
        detail={`${totals.failed} failed · ${totals.timedOut} timed out · ${totals.cancelled} cancelled`}
        compare={compare}
        testId="insights-tile-success"
      />
      <HeadlineTile
        label="Typical duration (p50)"
        value={formatSucceededDuration(totals.p50Duration, totals.succeeded)}
        delta={timed ? relativeDelta(totals.p50Duration, previous.p50Duration, false) : null}
        comparedWith={`${previousLabel} (${formatSucceededDuration(previous.p50Duration, previous.succeeded)})`}
        detail={`Queue wait p50 ${formatDuration(totals.p50QueueWait)} · p95 ${formatDuration(totals.p95QueueWait)}`}
        compare={compare}
        testId="insights-tile-p50"
      />
      <HeadlineTile
        label="Slow tail (p95)"
        value={formatSucceededDuration(totals.p95Duration, totals.succeeded)}
        delta={timed ? relativeDelta(totals.p95Duration, previous.p95Duration, false) : null}
        comparedWith={`${previousLabel} (${formatSucceededDuration(previous.p95Duration, previous.succeeded)})`}
        detail={slowest ? `Longest ${formatDuration(totals.maxDuration)} · ${slowest.workflowName}` : `Longest ${formatDuration(totals.maxDuration)}`}
        compare={compare}
        testId="insights-tile-p95"
      />
    </div>
  );
}
