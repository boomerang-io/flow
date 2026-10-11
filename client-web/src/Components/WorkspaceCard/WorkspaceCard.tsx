import React, { useEffect, useState } from "react";
import { Link, useFetcher, useNavigate } from "react-router-dom";
import { InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { ConfirmModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { appLink, FeatureFlag } from "Config/appConfig";
import { ArrowRight } from "@carbon/react/icons";
import cx from "classnames";
import moment from "moment";
import { ExecutionStatusCopy } from "Constants";
import { FlowWorkspaceSummary, RunStatus } from "Types";
import { isActionError } from "Utils/actionResult";
import styles from "./workspaceCard.module.scss";

/** Home's per-workspace rollup (Features/Home/homeLoader.ts). Absent when that read degraded. */
export interface WorkspaceCardStats {
  runsToday: number;
  lastRun: { workflowName: string; workflowRef: string; status: RunStatus; creationDate: string } | null;
}

interface WorkspaceCardProps {
  workspace: FlowWorkspaceSummary;
  stats?: WorkspaceCardStats;
}

// Submits to Home's `action` (Features/Home/Home.tsx, intent "leave-workspace") - this card is
// only ever rendered inside the Home route, with no route boundary in between, so a plain
// useFetcher() submission with no explicit `action` target lands there by default.
type LeaveWorkspaceActionResult =
  | { intent: "leave-workspace"; displayName: string }
  | { intent: "leave-workspace"; displayName: string; error: { title: string; message: string } };

const dotClass: Partial<Record<RunStatus, string>> = {
  [RunStatus.Succeeded]: styles.dotSucceeded,
  [RunStatus.Failed]: styles.dotFailed,
  [RunStatus.TimedOut]: styles.dotFailed,
  [RunStatus.Invalid]: styles.dotFailed,
  [RunStatus.Running]: styles.dotRunning,
  [RunStatus.Waiting]: styles.dotWaiting,
};

const WorkspaceCard: React.FC<WorkspaceCardProps> = ({ workspace, stats }) => {
  const [isLeaveModalOpen, setIsLeaveModalOpen] = useState(false);
  const navigate = useNavigate();
  // The workspace list this card renders comes from useAppContext(), fed by the root loader
  // (Features/App/App.tsx). React Router revalidates every matched loader - root included - once
  // this fetcher's action settles, so no explicit refresh call is needed here.
  const fetcher = useFetcher<LeaveWorkspaceActionResult>();

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (!isActionError(fetcher.data)) {
      notify(
        <ToastNotification kind="success" title={`Leave Workspace`} subtitle={`${fetcher.data.displayName} successfully left`} />,
      );
    } else {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle={`Request to leave workspace failed`} />);
    }
  }, [fetcher.state, fetcher.data]);

  const handleLeaveWorkspace = () => {
    fetcher.submit({ intent: "leave-workspace", workspace: workspace.name, displayName: workspace.displayName }, { method: "post" });
  };

  const isLeaving = fetcher.state !== "idle";
  const activityEnabled = Boolean(useFeature(FeatureFlag.ActivityEnabled));
  const workspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);
  const singleWorkspaceEnabled = useFeature(FeatureFlag.SingleWorkspaceEnabled);
  const workspaceArg = { workspace: workspace.name };

  const menuOptions = [
    { itemText: "View Workflows", onClick: () => navigate(appLink.workflows(workspaceArg)) },
    { itemText: "View Actions", onClick: () => navigate(appLink.actions(workspaceArg)) },
    ...(activityEnabled ? [{ itemText: "View Activity", onClick: () => navigate(appLink.activity(workspaceArg)) }] : []),
    ...(workspaceManagementEnabled
      ? [{ itemText: "Manage Workspace", onClick: () => navigate(appLink.manageWorkspace(workspaceArg)) }]
      : []),
    // The only workspace cannot be left.
    ...(!singleWorkspaceEnabled
      ? [{ hasDivider: true, itemText: "Leave", isDelete: true, onClick: () => setIsLeaveModalOpen(true), disabled: false }]
      : []),
  ];

  const lastRun = stats?.lastRun ?? null;
  const isInactive = workspace.status !== "active";

  return (
    <article className={styles.container} data-testid="workspace-card" aria-label={workspace.displayName}>
      <header className={styles.header}>
        <div className={styles.heading}>
          <Link
            to={appLink.workflows(workspaceArg)}
            className={styles.displayName}
            title={workspace.displayName}
            data-testid="workflow-card-title"
          >
            {workspace.displayName}
          </Link>
          <span className={styles.slug}>{workspace.name}</span>
        </div>
        {!isLeaving ? (
          <OverflowMenu flipped ariaLabel="Workspace options" iconDescription="Workspace options" size="sm">
            {menuOptions.map(({ onClick, itemText, ...rest }, index) => (
              <OverflowMenuItem onClick={onClick} itemText={itemText} key={`${itemText}-${index}`} {...rest} />
            ))}
          </OverflowMenu>
        ) : null}
      </header>
      <dl className={styles.metrics}>
        <div className={styles.metric}>
          <dt>Workflows</dt>
          <dd>{workspace.insights?.workflows ?? "---"}</dd>
        </div>
        <div className={styles.metric}>
          <dt>Members</dt>
          <dd>{workspace.insights?.members ?? "---"}</dd>
        </div>
        <div className={styles.metric}>
          <dt>Runs today</dt>
          <dd>{stats ? stats.runsToday : "---"}</dd>
        </div>
      </dl>
      <p className={styles.status}>
        {isLeaving ? (
          <InlineLoading description="Leaving.." style={{ width: "fit-content" }} />
        ) : isInactive ? (
          <>
            <span className={cx(styles.dot, styles.dotFailed)} aria-hidden="true" />
            Inactive
          </>
        ) : lastRun ? (
          <>
            <span className={cx(styles.dot, dotClass[lastRun.status] ?? styles.dotIdle)} aria-hidden="true" />
            Last run {ExecutionStatusCopy[lastRun.status]?.toLowerCase() ?? lastRun.status} ·{" "}
            {lastRun.workflowName || lastRun.workflowRef} · {moment(lastRun.creationDate).fromNow()}
          </>
        ) : (
          <>
            <span className={cx(styles.dot, styles.dotIdle)} aria-hidden="true" />
            No runs yet · created {moment(workspace.creationDate).format("YYYY-MM-DD")}
          </>
        )}
      </p>
      <nav className={styles.footer} aria-label={`${workspace.displayName} links`}>
        <Link to={appLink.workflows(workspaceArg)}>Workflows</Link>
        {activityEnabled ? <Link to={appLink.activity(workspaceArg)}>Activity</Link> : null}
        <Link to={appLink.actions(workspaceArg)}>Actions</Link>
        <Link to={appLink.workflows(workspaceArg)} className={styles.open} aria-label={`Open ${workspace.displayName}`}>
          Open <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </nav>
      {isLeaveModalOpen && (
        <ConfirmModal
          affirmativeAction={handleLeaveWorkspace}
          affirmativeButtonProps={{ kind: "danger" }}
          affirmativeText="Leave"
          isOpen={isLeaveModalOpen}
          negativeAction={() => {
            setIsLeaveModalOpen(false);
          }}
          negativeText="Cancel"
          onCloseModal={() => {
            setIsLeaveModalOpen(false);
          }}
          title={`Leave Workspace`}
        >
          {`Are you sure you want to leave Workspace (${workspace.displayName})? There's no going back from this decision.`}
        </ConfirmModal>
      )}
    </article>
  );
};

export default WorkspaceCard;
