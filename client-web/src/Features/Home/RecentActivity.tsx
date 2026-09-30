import React from "react";
import { Link } from "react-router-dom";
import { Tag } from "@carbon/react";
import { getHumanizedDuration } from "@boomerang-io/utils";
import moment from "moment";
import { appLink } from "Config/appConfig";
import { ExecutionStatusCopy } from "Constants";
import { RunStatus } from "Types";
import { HomeRun } from "./homeLoader";
import styles from "./recentActivity.module.scss";

type TagType = "red" | "blue" | "purple" | "green" | "gray";

// Carbon tag colours keyed by run status - the same reading the Activity table's icons give.
const statusTag: Record<RunStatus, TagType> = {
  [RunStatus.Succeeded]: "green",
  [RunStatus.Failed]: "red",
  [RunStatus.TimedOut]: "red",
  [RunStatus.Invalid]: "red",
  [RunStatus.Running]: "blue",
  [RunStatus.Waiting]: "purple",
  [RunStatus.Ready]: "gray",
  [RunStatus.NotStarted]: "gray",
  [RunStatus.Cancelled]: "gray",
  [RunStatus.Skipped]: "gray",
};

function formatDuration(duration: number): string {
  if (!duration) {
    return "---";
  }
  return duration < 1000 ? "< 1 second" : getHumanizedDuration(Math.round(duration / 1000));
}

export default function RecentActivity({ runs }: { runs: Array<HomeRun> }) {
  if (runs.length === 0) {
    return (
      <div className={styles.table}>
        <p className={styles.empty}>No runs yet. Run a workflow and it shows up here.</p>
      </div>
    );
  }
  return (
    <div className={styles.table} role="table" aria-label="Recent runs">
      <div className={styles.head} role="row">
        <span role="columnheader">Status</span>
        <span role="columnheader">Workflow</span>
        <span role="columnheader" className={styles.workspace}>
          Workspace
        </span>
        <span role="columnheader" className={styles.trigger}>
          Trigger
        </span>
        <span role="columnheader" className={styles.duration}>
          Duration
        </span>
        <span role="columnheader" className={styles.started}>
          Started
        </span>
      </div>
      {runs.map((run) => (
        <Link
          key={run.id}
          className={styles.row}
          role="row"
          to={appLink.execution({ workspace: run.workspace, runId: run.id })}
          data-testid="home-recent-run"
        >
          <span role="cell">
            <Tag type={statusTag[run.status] ?? "gray"} size="sm">
              {ExecutionStatusCopy[run.status] ?? run.status}
            </Tag>
          </span>
          <span role="cell" className={styles.name} title={run.workflowName}>
            {run.workflowName || run.workflowRef}
          </span>
          <span role="cell" className={styles.workspace}>
            {run.workspaceDisplayName}
          </span>
          <span role="cell" className={styles.trigger}>
            {run.trigger || "---"}
          </span>
          <span role="cell" className={styles.duration}>
            {formatDuration(run.duration)}
          </span>
          <time role="cell" className={styles.started} dateTime={run.creationDate}>
            {moment(run.creationDate).fromNow()}
          </time>
        </Link>
      ))}
    </div>
  );
}
