import React from "react";
import { Link } from "react-router-dom";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@carbon/react";
import cx from "classnames";
import moment from "moment";
import { appLink } from "Config/appConfig";
import { InsightsDay, InsightsWorkflow } from "Types";
import { formatDuration, formatPercent } from "./format";
import styles from "./Insights.module.scss";

interface WorkflowTableProps {
  workspace: string;
  rows: Array<InsightsWorkflow>;
  /** The selected row's workflow name; its detail renders below the table. */
  selected: string | null;
  /** Builds the search string that selects a workflow, keeping every other filter. */
  selectHref: (workflowName: string) => string;
}

function successTone(rate: number | null): string {
  if (rate === null) {
    return styles.barNeutral;
  }
  return rate >= 0.9 ? styles.barGood : rate >= 0.75 ? styles.barWarn : styles.barBad;
}

/** Seven small stacked bars, one per day, tallest day at full height. */
function RecentDays({ days }: { days: Array<InsightsDay> }) {
  const tallest = Math.max(1, ...days.map((day) => day.succeeded + day.failed + day.timedOut + day.cancelled + day.other));
  return (
    <span className={styles.sparkline} aria-hidden="true">
      {days.map((day) => {
        const total = day.succeeded + day.failed + day.timedOut + day.cancelled + day.other;
        const bad = day.failed + day.timedOut;
        return (
          <span key={day.date} className={styles.sparkDay} title={`${day.date}: ${total} runs, ${bad} not succeeded`}>
            <span className={styles.sparkBad} style={{ height: `${(bad / tallest) * 100}%` }} />
            <span className={styles.sparkGood} style={{ height: `${((total - bad) / tallest) * 100}%` }} />
          </span>
        );
      })}
    </span>
  );
}

/** One row per workflow, worst success rate first: the object the reader acts on. */
export default function WorkflowTable({ workspace, rows, selected, selectHref }: WorkflowTableProps) {
  return (
    <div className={styles.panel}>
      <div className={styles.panelHeader}>
        <h2 className={styles.panelTitle}>By workflow</h2>
        <span className={styles.panelHint}>Worst success rate first · select a row for detail</span>
      </div>
      <Table size="md" aria-label="Workflow statistics" useZebraStyles={false}>
        <TableHead>
          <TableRow>
            <TableHeader>Workflow</TableHeader>
            <TableHeader className={styles.numeric}>Runs</TableHeader>
            <TableHeader>Success</TableHeader>
            <TableHeader>p50</TableHeader>
            <TableHeader>p95</TableHeader>
            <TableHeader>Last failure</TableHeader>
            <TableHeader>Last 7 days</TableHeader>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((row) => (
            <TableRow
              key={row.workflowRef}
              className={cx({ [styles.rowSelected]: row.workflowName === selected })}
              data-testid="insights-workflow-row"
            >
              <TableCell>
                <Link to={selectHref(row.workflowName)} className={styles.rowLink} aria-current={row.workflowName === selected ? "true" : undefined}>
                  {row.workflowName}
                </Link>
              </TableCell>
              <TableCell className={styles.numeric}>{row.runs}</TableCell>
              <TableCell>
                <span className={styles.successCell}>
                  <span className={styles.bar} aria-hidden="true">
                    <span className={cx(styles.barFill, successTone(row.successRate))} style={{ width: `${(row.successRate ?? 0) * 100}%` }} />
                  </span>
                  {formatPercent(row.successRate)}
                </span>
              </TableCell>
              <TableCell>{formatDuration(row.p50Duration)}</TableCell>
              <TableCell>{formatDuration(row.p95Duration)}</TableCell>
              <TableCell>
                {row.lastFailureDate && row.lastFailureRunRef ? (
                  <Link to={appLink.execution({ workspace, runId: row.lastFailureRunRef })}>{moment(row.lastFailureDate).fromNow()}</Link>
                ) : (
                  <span className={styles.muted}>none</span>
                )}
              </TableCell>
              <TableCell>
                <RecentDays days={row.recent} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
