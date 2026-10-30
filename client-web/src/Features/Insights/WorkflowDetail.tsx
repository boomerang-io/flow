import React from "react";
import { Link } from "react-router-dom";
import { Tag } from "@carbon/react";
import moment from "moment";
import { appLink } from "Config/appConfig";
import cx from "classnames";
import { InsightsTaskRow, InsightsWorkflowDetail } from "Types";
import { formatCost, formatTokens } from "./format";
import { formatDuration } from "Utils/dateHelper";
import UsagePanel from "./UsagePanel";
import styles from "./Insights.module.scss";

/** "12,345 tokens · 0.0021"; empty for a task that is not an ai task. */
function taskUsage(task: InsightsTaskRow): string {
  if (task.totalTokens === null || task.totalTokens === undefined) {
    return "";
  }
  const tokens = `${formatTokens(task.totalTokens)} tokens`;
  return task.cost === null || task.cost === undefined ? tokens : `${tokens} · ${formatCost(task.cost)}`;
}

type TagType = "red" | "purple" | "gray" | "teal";

const failureTag: Record<string, { type: TagType; label: string }> = {
  failed: { type: "red", label: "Failed" },
  timedout: { type: "teal", label: "Timed out" },
  invalid: { type: "red", label: "Invalid" },
  cancelled: { type: "gray", label: "Cancelled" },
};

interface WorkflowDetailProps {
  workspace: string;
  detail: InsightsWorkflowDetail;
}

/** The selected workflow: where a typical run spends its time, and why runs did not succeed. */
export default function WorkflowDetail({ workspace, detail }: WorkflowDetailProps) {
  const longest = Math.max(1, ...detail.tasks.map((task) => task.p50Duration), detail.p50QueueWait);
  const failed = detail.failures.reduce((sum, failure) => sum + failure.count, 0);
  // Task rows carry a usage column only when the workflow ran an ai task in the period.
  const usage = detail.usage && detail.usage.aiTaskRuns > 0 ? detail.usage : null;
  const rowClass = cx(styles.taskRow, { [styles.taskRowUsage]: usage });

  return (
    <div className={styles.detail} data-testid="insights-workflow-detail">
      <section className={styles.subPanel} aria-labelledby="insights-time-title">
        <div className={styles.panelHeader}>
          <h2 id="insights-time-title" className={styles.panelTitle}>
            Where the time goes · {detail.workflowName}
          </h2>
          <span className={styles.panelHint}>p50 per task, succeeded task runs</span>
        </div>
        {detail.tasks.length === 0 ? (
          <p className={styles.empty}>No task runs in this period.</p>
        ) : (
          <ul className={styles.taskList}>
            {detail.tasks.map((task) => (
              <li key={task.name} className={rowClass}>
                <span className={styles.taskName} title={task.taskRef ?? undefined}>
                  {task.name}
                </span>
                <span className={styles.bar} aria-hidden="true">
                  <span className={`${styles.barFill} ${styles.barTask}`} style={{ width: `${(task.p50Duration / longest) * 100}%` }} />
                </span>
                <span className={styles.taskValue}>
                  {formatDuration(task.p50Duration)}
                  {task.failed > 0 ? <span className={styles.muted}> · {task.failed} failed</span> : null}
                </span>
                {usage ? (
                  <span className={`${styles.taskValue} ${styles.muted}`} data-testid={`insights-task-usage-${task.name}`}>
                    {taskUsage(task)}
                  </span>
                ) : null}
              </li>
            ))}
            <li className={rowClass}>
              <span className={`${styles.taskName} ${styles.muted}`}>queue wait</span>
              <span className={styles.bar} aria-hidden="true">
                <span className={`${styles.barFill} ${styles.barNeutral}`} style={{ width: `${(detail.p50QueueWait / longest) * 100}%` }} />
              </span>
              <span className={`${styles.taskValue} ${styles.muted}`}>{formatDuration(detail.p50QueueWait)}</span>
              {usage ? <span /> : null}
            </li>
          </ul>
        )}
        <p className={styles.panelNote}>
          {detail.retriedRuns > 0 ? `${detail.retriedRuns} of ${detail.runs} runs were retries. ` : ""}
          Queue wait p95 {formatDuration(detail.p95QueueWait)}.
        </p>
      </section>

      <section className={styles.subPanel} aria-labelledby="insights-failures-title">
        <div className={styles.panelHeader}>
          <h2 id="insights-failures-title" className={styles.panelTitle}>
            Why it fails · {detail.workflowName}
          </h2>
          <span className={styles.panelHint}>
            {failed} of {detail.runs} runs
          </span>
        </div>
        {detail.failures.length === 0 ? (
          <p className={styles.empty}>Every run in this period succeeded.</p>
        ) : (
          <ul className={styles.failureList}>
            {detail.failures.map((failure) => {
              const tag = failureTag[failure.status] ?? { type: "gray" as TagType, label: failure.status };
              return (
                <li key={`${failure.status}-${failure.taskName}-${failure.reason}`} className={styles.failureRow}>
                  <span className={styles.failureCount}>{failure.count}</span>
                  <span className={styles.failureBody}>
                    <Tag type={tag.type} size="sm">
                      {tag.label}
                    </Tag>
                    <span>
                      {failure.taskName ? <strong>{failure.taskName}</strong> : null}
                      {failure.taskName && failure.reason ? " · " : ""}
                      {failure.reason ?? (failure.taskName ? "" : "No task failed; see the run")}
                    </span>
                  </span>
                  <Link to={appLink.execution({ workspace, runId: failure.lastRunRef })} className={styles.failureLink}>
                    last: {moment(failure.lastDate).fromNow()}
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
        <p className={styles.panelNote}>Each row links to the newest run that ended that way.</p>
      </section>

      {usage ? (
        <UsagePanel className={styles.subPanel} id="insights-detail-usage" title={`Model usage · ${detail.workflowName}`} usage={usage} />
      ) : null}
    </div>
  );
}
