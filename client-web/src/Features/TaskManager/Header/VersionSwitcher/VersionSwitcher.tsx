import React from "react";
import { ChevronLeft, ChevronRight, PageFirst, PageLast } from "@carbon/react/icons";
import cx from "classnames";
import { useNavigate, useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { Task } from "Types";
import styles from "./VersionSwitcher.module.scss";

interface VersionSwitcherProps {
  selectedTaskTemplate: Task;
  versionCount: number;
  canEdit: boolean;
}

const VersionSwitcher: React.FC<VersionSwitcherProps> = ({ selectedTaskTemplate, versionCount, canEdit }) => {
  const navigate = useNavigate();
  const rawParams = useParams<{ name: string; workspace: string }>();
  const params = { name: rawParams.name ?? "", workspace: rawParams.workspace ?? "" };
  const backVersion = () => {
    navigate(
      params.workspace
        ? appLink.manageTasksEdit({
            workspace: params.workspace,
            name: params.name,
            version: "" + (selectedTaskTemplate.version - 1),
          })
        : appLink.adminTasksDetail({
            name: params.name,
            version: "" + (selectedTaskTemplate.version - 1),
          }),
    );
  };

  const fastBackVersion = () => {
    navigate(
      params.workspace
        ? appLink.manageTasksEdit({ workspace: params.workspace, name: params.name, version: "1" })
        : appLink.adminTasksDetail({
            name: params.name,
            version: "1",
          }),
    );
  };

  const forwardVersion = () => {
    navigate(
      params.workspace
        ? appLink.manageTasksEdit({
            workspace: params.workspace,
            name: params.name,
            version: "" + (selectedTaskTemplate.version + 1),
          })
        : appLink.adminTasksDetail({
            name: params.name,
            version: "" + (selectedTaskTemplate.version + 1),
          }),
    );
  };

  const fastForwardVersion = () => {
    navigate(
      params.workspace
        ? appLink.manageTasksEdit({ workspace: params.workspace, name: params.name, version: "" + versionCount })
        : appLink.adminTasksDetail({
            name: params.name,
            version: "" + versionCount,
          }),
    );
  };

  const renderBackButtons = (enabled: boolean) => {
    return (
      <div className={styles.buttonList}>
        <button className={styles.button} disabled={!enabled} onClick={fastBackVersion}>
          <PageFirst className={cx(styles.icon, { [styles.disabled]: !enabled })} aria-label="first version" />
        </button>
        <button className={styles.button} disabled={!enabled} onClick={backVersion}>
          <ChevronLeft className={cx(styles.icon, { [styles.disabled]: !enabled })} aria-label="back one version" />
        </button>
      </div>
    );
  };

  const renderForwardButtons = (enabled: boolean) => {
    return (
      <div className={styles.buttonList}>
        <button className={styles.button} disabled={!enabled} onClick={forwardVersion}>
          <ChevronRight className={cx(styles.icon, { [styles.disabled]: !enabled })} aria-label="forward one version" />
        </button>
        <button className={styles.button} disabled={!enabled} onClick={fastForwardVersion}>
          <PageLast className={cx(styles.icon, { [styles.disabled]: !enabled })} aria-label="last version" />
        </button>
      </div>
    );
  };

  return (
    <div className={styles.container}>
      <div className={styles.buttonListContainer}>
        {renderBackButtons(selectedTaskTemplate.version > 1)}
        <p className={styles.versionText}>{`Version ${selectedTaskTemplate.version || 1}`}</p>
        {renderForwardButtons(selectedTaskTemplate.version < versionCount)}
      </div>
    </div>
  );
};

export default VersionSwitcher;
