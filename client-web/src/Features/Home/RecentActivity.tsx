import React from "react";
import { Link } from "react-router-dom";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow, Tag } from "@carbon/react";
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

/** The newest runs across the caller's workspaces, one real table row each; the name opens the run. */
export default function RecentActivity({ runs }: { runs: Array<HomeRun> }) {
  if (runs.length === 0) {
    return (
      <div className={styles.table}>
        <p className={styles.empty}>No runs yet. Run a workflow and it shows up here.</p>
      </div>
    );
  }
  return (
    <div className={styles.table}>
      <Table size="md" aria-label="Recent runs" useZebraStyles={false}>
        <TableHead>
          <TableRow>
            <TableHeader>Status</TableHeader>
            <TableHeader>Workflow</TableHeader>
            <TableHeader className={styles.wide}>Workspace</TableHeader>
            <TableHeader className={styles.wide}>Trigger</TableHeader>
            <TableHeader className={styles.wide}>Duration</TableHeader>
            <TableHeader className={styles.started}>Started</TableHeader>
          </TableRow>
        </TableHead>
        <TableBody>
          {runs.map((run) => (
            <TableRow key={run.id} data-testid="home-recent-run">
              <TableCell>
                <Tag type={statusTag[run.status] ?? "gray"} size="sm">
                  {ExecutionStatusCopy[run.status] ?? run.status}
                </Tag>
              </TableCell>
              <TableCell>
                <Link className={styles.name} to={appLink.execution({ workspace: run.workspace, runId: run.id })}>
                  {run.workflowName || run.workflowRef}
                </Link>
              </TableCell>
              <TableCell className={`${styles.muted} ${styles.wide}`}>{run.workspaceDisplayName}</TableCell>
              <TableCell className={`${styles.muted} ${styles.wide} ${styles.trigger}`}>{run.trigger || "---"}</TableCell>
              <TableCell className={`${styles.muted} ${styles.wide}`}>{formatDuration(run.duration)}</TableCell>
              <TableCell className={`${styles.muted} ${styles.started}`}>
                <time dateTime={run.creationDate}>{moment(run.creationDate).fromNow()}</time>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
