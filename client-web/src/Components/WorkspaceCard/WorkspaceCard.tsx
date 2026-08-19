import React, { useState } from "react";
import { useMutation, useQueryClient } from "react-query";
import { Link, useNavigate } from "react-router-dom";
import { InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { ConfirmModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { ArrowRight, Checkmark, Close } from "@carbon/react/icons";
import moment from "moment";
import { FlowWorkspaceSummary } from "Types";
import styles from "./workspaceCard.module.scss";

interface WorkspaceCardProps {
  workspace: FlowWorkspaceSummary;
}

const WorkspaceCard: React.FC<WorkspaceCardProps> = ({ workspace }) => {
  const [isLeaveModalOpen, setIsLeaveModalOpen] = useState(false);
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const leaveWorkspaceMutator = useMutation(resolver.leaveWorkspace);
  const handleLeaveWorkspace = async () => {
    try {
      await leaveWorkspaceMutator.mutateAsync({ workspace: workspace.name });
      notify(
        <ToastNotification kind="success" title={`Leave Workspace`} subtitle={`${workspace.displayName} successfully left`} />,
      );
      queryClient.invalidateQueries(serviceUrl.getUserProfile());
    } catch {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle={`Request to leave workspace failed`} />);
    }
  };

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
      <Link to={!leaveWorkspaceMutator.isLoading ? appLink.workflows({ workspace: workspace.name }) : ""}>
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
                {leaveWorkspaceMutator.isLoading ? (
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
      {!leaveWorkspaceMutator.isLoading ? (
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
