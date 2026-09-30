import React from "react";
import cx from "classnames";
import { InsightsWorkflow } from "Types";
import { formatDuration } from "./format";
import styles from "./Insights.module.scss";

const SECOND = 1000;

/** Position of a duration on a log scale between min and max, as a percentage. */
function position(ms: number, min: number, max: number): number {
  const clamped = Math.min(Math.max(ms, min), max);
  return ((Math.log10(clamped) - Math.log10(min)) / (Math.log10(max) - Math.log10(min))) * 100;
}

/**
 * Each workflow's duration spread on one log scale: a bar from p5 to p95, a mark at p50, and the
 * run timeout where one is set. Replaces a scatter of every run, which is a cloud at zero with
 * a few outliers.
 */
export default function DurationSpread({ rows }: { rows: Array<InsightsWorkflow> }) {
  const withRuns = rows.filter((row) => row.succeeded > 0);
  if (withRuns.length === 0) {
    return null;
  }
  const min = Math.max(SECOND, Math.min(...withRuns.map((row) => row.p5Duration || SECOND)));
  const max = Math.max(
    min * 10,
    ...withRuns.map((row) => Math.max(row.p95Duration, (row.timeoutMinutes ?? 0) * 60 * SECOND)),
  );
  const ticks: Array<number> = [];
  for (let tick = SECOND; tick <= max * 10; tick *= 10) {
    if (tick >= min && tick <= max) {
      ticks.push(tick);
    }
  }

  return (
    <div className={styles.panel} data-testid="insights-duration-spread">
      <div className={styles.panelHeader}>
        <h2 className={styles.panelTitle}>Duration spread, by workflow</h2>
        <span className={styles.panelHint}>bar = p5 to p95 · mark = p50 · log scale · red mark = run timeout</span>
      </div>
      <ul className={styles.spreadList}>
        {withRuns.map((row) => {
          const timeoutMs = (row.timeoutMinutes ?? 0) * 60 * SECOND;
          return (
            <li key={row.workflowRef} className={styles.spreadRow}>
              <span className={styles.taskName}>{row.workflowName}</span>
              <span
                className={styles.spreadTrack}
                role="img"
                aria-label={`${row.workflowName}: p5 ${formatDuration(row.p5Duration)}, p50 ${formatDuration(row.p50Duration)}, p95 ${formatDuration(row.p95Duration)}`}
              >
                <span
                  className={styles.spreadBar}
                  style={{
                    left: `${position(row.p5Duration || min, min, max)}%`,
                    width: `${Math.max(0.5, position(row.p95Duration || min, min, max) - position(row.p5Duration || min, min, max))}%`,
                  }}
                />
                <span className={styles.spreadMark} style={{ left: `${position(row.p50Duration || min, min, max)}%` }} />
                {timeoutMs > 0 ? (
                  <span className={cx(styles.spreadMark, styles.spreadTimeout)} style={{ left: `${position(timeoutMs, min, max)}%` }} />
                ) : null}
              </span>
            </li>
          );
        })}
        <li className={cx(styles.spreadRow, styles.spreadAxis)} aria-hidden="true">
          <span />
          <span className={styles.spreadTrack}>
            {ticks.map((tick) => (
              <span key={tick} className={styles.spreadTick} style={{ left: `${position(tick, min, max)}%` }}>
                {formatDuration(tick)}
              </span>
            ))}
          </span>
        </li>
      </ul>
    </div>
  );
}
