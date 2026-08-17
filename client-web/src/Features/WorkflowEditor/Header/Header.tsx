import React from "react";
import { Breadcrumb, BreadcrumbItem, Button } from "@carbon/react";
import { Add, DocumentExport } from "@carbon/react/icons";
import {
  ConfirmModal,
  ComposedModal,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { AxiosResponse } from "axios";
import { UseMutationResult } from "react-query";
import { Link, useParams } from "react-router-dom";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { ModalTriggerProps, WorkflowCanvas, WorkflowViewType, ChangeLog } from "Types";
import VersionCommentForm from "./VersionCommentForm";
import VersionSwitcher from "./VersionSwitcher";
import styles from "./header.module.scss";

interface DesignerHeaderProps {
  changeLog: ChangeLog;
  createRevision: (reason: string, callback?: () => any) => void;
  changeRevision: (revisionNumber: string) => void;
  canCreateNewVersion: boolean;
  revisionCount: number;
  revisionMutator: UseMutationResult<
    AxiosResponse<any, any>,
    unknown,
    { team: any; workflow: any; body: any },
    unknown
  >;
  revisionState: WorkflowCanvas;

  viewType: WorkflowViewType;
}

const DesignerHeader: React.FC<DesignerHeaderProps> = ({
  createRevision,
  changeRevision,
  canCreateNewVersion,
  revisionCount,
  revisionMutator,
  revisionState,
  viewType,
}) => {
  const params = useParams<{ team: string; workflow: string }>();
  const { displayName } = revisionState;
  const { version: currentRevision } = revisionState;
  const isPreviousVersion = currentRevision < revisionCount;
  const performActionButtonText = currentRevision < revisionCount ? "Set version to latest" : "Create new version";

  // Need to hardcode that the version is being reset in the change log for now based on the current implementation
  const resetVersionToLatestWithMessage = () => {
    createRevision(`Set ${currentRevision} to the latest version`);
  };

  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{team ? team.displayName : "---"}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
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
              <Link to={appLink.workflows({ team: params.team })}>Workflows</Link>
            ) : (
              <Link to={appLink.templateWorkflows()}>Template Workflows</Link>
            )}
          </BreadcrumbItem>
          <BreadcrumbItem isCurrentPage>
            <p>{displayName}</p>
          </BreadcrumbItem>
        </Breadcrumb>
      }
      header={<HeaderTitle>Editor</HeaderTitle>}
      footer={
        <Tabs ariaLabel="Editor pages">
          <Tab label="Canvas" to={appLink.editorCanvas({ team: params.team, workflow: params.workflow })} />
          <Tab label="Parameters" to={appLink.editorProperties({ team: params.team, workflow: params.workflow })} />
          <Tab label="Configure" to={appLink.editorConfigure({ team: params.team, workflow: params.workflow })} />
          <Tab label="Schedules" to={appLink.editorSchedule({ team: params.team, workflow: params.workflow })} />
          <Tab label="Change Log" to={appLink.editorChangelog({ team: params.team, workflow: params.workflow })} />
        </Tabs>
      }
      actions={
        <section className={styles.workflowButtons}>
          <VersionSwitcher
            currentRevision={currentRevision}
            disabled={!canCreateNewVersion}
            onChangeVersion={changeRevision}
            revisionCount={revisionCount}
          />
          <div className={styles.workflowActionContainer}>
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
                    style={!isPreviousVersion ? { display: "none" } : null}
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
                    style={isPreviousVersion ? { display: "none" } : null}
                  >
                    {performActionButtonText}
                  </Button>
                )}
              >
                {({ closeModal }) => (
                  <VersionCommentForm
                    closeModal={closeModal}
                    createRevision={createRevision}
                    revisionMutator={revisionMutator}
                  />
                )}
              </ComposedModal>
            </>
          </div>
        </section>
      }
    />
  );
};

export default DesignerHeader;
