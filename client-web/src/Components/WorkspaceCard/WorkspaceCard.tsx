import React, { useEffect, useState } from "react";
import { Link, useFetcher, useNavigate, useRevalidator } from "react-router-dom";
import { InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { ConfirmModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import { ArrowRight, Checkmark, Close } from "@carbon/react/icons";
import moment from "moment";
import { FlowWorkspaceSummary } from "Types";
import styles from "./workspaceCard.module.scss";

interface WorkspaceCardProps {
  workspace: FlowWorkspaceSummary;
}

// Submits to Home's `action` (Features/Home/Home.tsx, intent "leave-workspace") - this card is
// only ever rendered inside the Home route, with no route boundary in between, so a plain
// useFetcher() submission with no explicit `action` target lands there by default.
type LeaveWorkspaceActionResult = {
  ok: boolean;
  intent: "leave-workspace";
  displayName: string;
};

const WorkspaceCard: React.FC<WorkspaceCardProps> = ({ workspace }) => {
  const [isLeaveModalOpen, setIsLeaveModalOpen] = useState(false);
  const navigate = useNavigate();
  // Home has no loader of its own yet (its data comes from useAppContext(), fed by App.tsx's
  // in-flight loader conversion); revalidate() is still the correct refresh call - it becomes
  // live the moment that loader lands, unlike queryClient.invalidateQueries, which would be an
  // inert no-op once it does (see UserLabels/ChangeRole for the bug that already caused).
  const revalidator = useRevalidator();
  const fetcher = useFetcher<LeaveWorkspaceActionResult>();

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.ok) {
      revalidator.revalidate();
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

  let menuOptions = [
    {
      itemText: "View Workflows",
      onClick: () => navigate(appLink.workflows({ workspace: workspace.name })),
    },
    {
      itemText: "View Actions",
      onClick: () => navigate(appLink.actions({ workspace: workspace.name })),
    },
    {
      itemText: "View Activity",
      onClick: () => navigate(appLink.activity({ workspace: workspace.name })),
    },
    {
      itemText: "Manage Workspace",
      onClick: () => navigate(appLink.manageWorkspace({ workspace: workspace.name })),
    },
    {
      hasDivider: true,
      itemText: "Leave",
      isDelete: true,
      onClick: () => setIsLeaveModalOpen(true),
      disabled: false,
    },
  ];

  return (
    <div className={styles.container}>
      <Link to={!isLeaving ? appLink.workflows({ workspace: workspace.name }) : ""}>
        <div className={styles.content}>
          <h1 title={workspace.displayName} className={styles.displayName} data-testid="workflow-card-title">
            {workspace.displayName}
          </h1>
          {/* TODO - change name to display name and put the name slug underneath in small font */}
          <div className={styles.details}>
            <div className={styles.detailItem}>
              <div className={styles.detailLabel}>Workflows</div>
              <div className={styles.detailValue}>{workspace.insights.workflows}</div>
            </div>
            <div className={styles.detailItem}>
              <div className={styles.detailLabel}>Members</div>
              <div className={styles.detailValue}>{workspace.insights.members}</div>
            </div>
            <div className={styles.detailItem}>
              <div className={styles.detailLabel}>Status</div>
              <div className={styles.detailValue}>
                {isLeaving ? (
                  <div className={styles.detailStatus}>
                    <InlineLoading description="Leaving.." style={{ width: "fit-content" }} />
                  </div>
                ) : (
                  <div className={styles.detailStatus}>
                    {workspace.status === "active" ? (
                      <Checkmark style={{ fill: "#009d9a" }} />
                    ) : (
                      <Close style={{ fill: "#da1e28" }} />
                    )}
                    <p>{workspace.status === "active" ? "Active" : "Inactive"}</p>
                  </div>
                )}
              </div>
            </div>
            <div className={styles.detailItem}>
              <div className={styles.detailLabel}>Creation Date</div>
              <div className={styles.detailValue}>{moment(workspace.creationDate).format("YYYY-MM-DD")}</div>
            </div>
          </div>
        </div>
        <ArrowRight size={24} className={styles.cardIcon} />
      </Link>
      {!isLeaving ? (
        <div style={{ position: "absolute", right: "0" }}>
          <OverflowMenu flipped ariaLabel="Overflow card menu" iconDescription="Overflow menu icon" size="sm">
            {menuOptions.map(({ onClick, itemText, ...rest }, index) => (
              <OverflowMenuItem onClick={onClick} itemText={itemText} key={`${itemText}-${index}`} {...rest} />
            ))}
          </OverflowMenu>
        </div>
      ) : null}
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
    </div>
  );
};

export default WorkspaceCard;
