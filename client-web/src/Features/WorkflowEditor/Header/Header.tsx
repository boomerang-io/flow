import React from "react";
import { Breadcrumb, BreadcrumbItem, Button, Tag } from "@carbon/react";
import { Add, DocumentExport, Locked, Save } from "@carbon/react/icons";
import {
  ConfirmModal,
  ComposedModal,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Link, useParams } from "react-router-dom";
import { WorkflowView } from "Constants";
import { appLink, FeatureFlag } from "Config/appConfig";
import { ModalTriggerProps, WorkflowCanvas, WorkflowViewType, ChangeLog } from "Types";
import VersionCommentForm from "./VersionCommentForm";
import VersionSwitcher from "./VersionSwitcher";
import styles from "./header.module.scss";

interface DesignerHeaderProps {
  changeLog: ChangeLog;
  createRevision: (reason: string, callback?: () => any) => void;
  changeRevision: (revisionNumber: string) => void;
  canCreateNewVersion: boolean;
  // Two plain flags rather than the react-query mutation object this used to be handed: the write
  // is a useFetcher() submission in Editor.tsx now (see its module comment), and a fetcher has no
  // equivalent object to pass down - only `state` and `data`, which the parent narrows for us.
  createRevisionFailed: boolean;
  isCreatingRevision: boolean;
  // A template, or a workflow that follows one: only its settings are saved, no versions are created.
  isReadOnlyTasks?: boolean;
  isSavingSettings?: boolean;
  saveSettings?: () => void;
  revisionCount: number;
  revisionState: WorkflowCanvas;

  viewType: WorkflowViewType;
}

const DesignerHeader: React.FC<DesignerHeaderProps> = ({
  createRevision,
  changeRevision,
  canCreateNewVersion,
  createRevisionFailed,
  isCreatingRevision,
  isReadOnlyTasks = false,
  isSavingSettings = false,
  saveSettings,
  revisionCount,
  revisionState,
  viewType,
}) => {
  const rawParams = useParams<{ workspace: string; workflow: string }>();
  const params = { workspace: rawParams.workspace ?? "", workflow: rawParams.workflow ?? "" };
  const schedulesEnabled = useFeature(FeatureFlag.SchedulesEnabled);
  const { displayName } = revisionState;
  const { version: currentRevision } = revisionState;
  const isPreviousVersion = currentRevision < revisionCount;
  const performActionButtonText = currentRevision < revisionCount ? "Set version to latest" : "Create new version";

  // Need to hardcode that the version is being reset in the change log for now based on the current implementation
  const resetVersionToLatestWithMessage = () => {
    createRevision(`Set ${currentRevision} to the latest version`);
  };

  return (
    <Header
      className={styles.container}
      nav={
        <Breadcrumb noTrailingSlash>
          <BreadcrumbItem>
            <Link to={appLink.home()}>Home</Link>
          </BreadcrumbItem>
          <BreadcrumbItem>
            {viewType === WorkflowView.Workflow ? (
              <Link to={appLink.workflows({ workspace: params.workspace })}>Workflows</Link>
            ) : (
              <Link to={appLink.home()}>Templates</Link>
            )}
          </BreadcrumbItem>
          <BreadcrumbItem isCurrentPage>
            <p>Editor</p>
          </BreadcrumbItem>
        </Breadcrumb>
      }
      // A page inside one workflow is titled with its name; the breadcrumb carries the route.
      header={
        <div className={styles.titleRow}>
          <HeaderTitle title={displayName}>{displayName}</HeaderTitle>
          {isReadOnlyTasks ? (
            <>
              <Tag type="purple" size="md">
                Template
              </Tag>
              <span className={styles.readOnly}>
                <Locked size={16} aria-hidden="true" />
                Read-only
              </span>
            </>
          ) : null}
        </div>
      }
      footer={
        // Tabs, the version switcher and the version actions share one row.
        <div className={styles.footer}>
          <Tabs ariaLabel="Editor pages">
            <Tab label="Canvas" to={appLink.editorCanvas({ workspace: params.workspace, workflow: params.workflow })} />
            <Tab label="Parameters" to={appLink.editorProperties({ workspace: params.workspace, workflow: params.workflow })} />
            <Tab label="Configure" to={appLink.editorConfigure({ workspace: params.workspace, workflow: params.workflow })} />
            {schedulesEnabled ? (
              <Tab label="Schedules" to={appLink.editorSchedule({ workspace: params.workspace, workflow: params.workflow })} />
            ) : null}
            <Tab label="Change Log" to={appLink.editorChangelog({ workspace: params.workspace, workflow: params.workflow })} />
          </Tabs>
          <section className={styles.workflowButtons}>
            <VersionSwitcher
              currentRevision={currentRevision}
              disabled={!canCreateNewVersion}
              onChangeVersion={changeRevision}
              revisionCount={revisionCount}
            />
            <div className={styles.workflowActionContainer}>
              {isReadOnlyTasks ? (
                <Button
                  disabled={!canCreateNewVersion || isSavingSettings}
                  iconDescription="Save settings"
                  kind="ghost"
                  onClick={saveSettings}
                  renderIcon={Save}
                  size="md"
                >
                  Save settings
                </Button>
              ) : (
              <>
                <ConfirmModal
                  affirmativeAction={resetVersionToLatestWithMessage}
                  children="A new version will be created"
                  title={`Set version ${currentRevision} to be the latest`}
                  modalTrigger={({ openModal }: ModalTriggerProps) => (
                    <Button
                      disabled={!canCreateNewVersion}
                      iconDescription="Set version to latest"
                      kind="ghost"
                      onClick={openModal}
                      renderIcon={DocumentExport}
                      size="md"
                      style={!isPreviousVersion ? { display: "none" } : undefined}
                    >
                      {performActionButtonText}
                    </Button>
                  )}
                />
                <ComposedModal
                  composedModalProps={{ containerClassName: styles.versionCommentModalContainer }}
                  modalHeaderProps={{
                    title: "Create New Version",
                    subtitle: "Enter a comment for record keeping",
                  }}
                  modalTrigger={({ openModal }: ModalTriggerProps) => (
                    <Button
                      disabled={!canCreateNewVersion}
                      iconDescription="Create new version"
                      kind="ghost"
                      onClick={openModal}
                      renderIcon={Add}
                      size="md"
                      style={isPreviousVersion ? { display: "none" } : undefined}
                    >
                      {performActionButtonText}
                    </Button>
                  )}
                >
                  {({ closeModal }) => (
                    <VersionCommentForm
                      closeModal={closeModal}
                      createRevision={createRevision}
                      createRevisionFailed={createRevisionFailed}
                      isCreatingRevision={isCreatingRevision}
                    />
                  )}
                </ComposedModal>
              </>
              )}
            </div>
          </section>
        </div>
      }
    />
  );
};

export default DesignerHeader;
