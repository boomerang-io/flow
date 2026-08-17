import React from "react";
import { Button, Tag, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import { Bee, Download, Save, Archive, Copy, Reset, ViewOff, Recommend, Identification } from "@carbon/react/icons";
import {
  ComposedModal,
  ConfirmModal,
  FeatureHeader,
  Loading,
  ModalFlowForm,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
  TextArea,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { FormikProps } from "formik";
import moment from "moment";
import { useParams } from "react-router-dom";
import VersionHistory from "Components/VersionHistory";
import { taskIcons } from "Utils/taskIcons";
import { appLink } from "Config/appConfig";
import { ModalTriggerProps, Task, ChangeLog } from "Types";
import { TemplateRequestType, FormProps } from "../constants";
import VersionSwitcher from "./VersionSwitcher";
import styles from "./header.module.scss";

const ArchiveText: React.FC = () => (
  <>
    <p className={styles.confirmModalText}>
      Archive a task when it is no longer supported and shouldn’t be used in new Workflows.
    </p>
    <p className={styles.confirmModalText}>
      Once archived, it will no longer appear in the Workflow Editor, but you can still view it in the Task Manager
      here. The task will remain functional in any existing Workflows to avoid breakage.
    </p>
    <p className={styles.confirmModalText}>You can restore an archived task later, if needed.</p>
  </>
);

interface SaveModalProps {
  name: string;
  formikProps: FormikProps<FormProps>;
  handleSubmit: (
    values: any,
    resetForm: () => void,
    requestType: string,
    setRequestError: ({ title, subtitle }: { title: string; subtitle: string }) => void,
    closeModal: () => void,
  ) => void;
  isLoading: boolean;
  canEdit: boolean;
}

const SaveModal: React.FC<SaveModalProps> = ({ formikProps, handleSubmit, isLoading, canEdit }) => {
  const [requestError, setRequestError] = React.useState<{ title?: string; subtitle?: string } | null>(null);
  const SaveMessage = () => {
    return (
      <>
        <p className={styles.saveConfirmModalText}>
          Choose to overwrite the current version, or save as a new version.
        </p>
        <p className={styles.saveConfirmModalText}>
          <strong>Overwritting the current version</strong> means that this task will automatically be updated in any
          workflow. Do this with extreme caution, and only when you haven’t made any breaking changes.
        </p>
        <p className={styles.saveConfirmModalText}>
          <strong>Saving as a new version</strong> will not automatically update this task in workflows. Users who have
          this task in a workflow will get a notification about the new version and can choose to update or not.
        </p>
      </>
    );
  };
  return (
    <ComposedModal
      modalHeaderProps={{
        title: "Save changes",
      }}
      composedModalProps={{ containerClassName: styles.saveContainer }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <TooltipHover direction="bottom" tooltipText={"Save a new version or update the current one"}>
          <Button
            className={styles.mainActionButton}
            disabled={!formikProps.isValid || !formikProps.dirty}
            size="md"
            renderIcon={Save}
            iconDescription="Save a new version or update the current one"
            onClick={openModal}
          >
            Save
          </Button>
        </TooltipHover>
      )}
    >
      {({ closeModal }) => {
        return (
          <ModalFlowForm>
            {isLoading && <Loading />}
            <ModalBody>
              <SaveMessage />
              <TextArea
                data-testid="save-comments"
                id="comments"
                name="comments"
                key="newTemplateComments"
                labelText="Comments (required for new versions)"
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  formikProps.setFieldValue("comments", e.target.value)
                }
                placeholder="Release notes for the new version"
                style={{ resize: "none" }}
                value={formikProps.values.comments}
              />
              {Boolean(requestError) && (
                <InlineNotification
                  lowContrast
                  style={{ marginBottom: "0.5rem" }}
                  kind="error"
                  title={requestError?.title}
                  subtitle={requestError?.subtitle}
                  onCloseButtonClick={() => setRequestError(null)}
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                kind="secondary"
                onClick={(e: React.MouseEvent<HTMLButtonElement>) => {
                  e.preventDefault();
                  handleSubmit(
                    formikProps.values,
                    formikProps.resetForm,
                    TemplateRequestType.Overwrite,
                    setRequestError,
                    closeModal,
                  );
                }}
              >
                Overwrite this version
              </Button>
              <Button
                onClick={(e: React.MouseEvent<HTMLButtonElement>) => {
                  e.preventDefault();
                  handleSubmit(
                    formikProps.values,
                    formikProps.resetForm,
                    TemplateRequestType.New,
                    setRequestError,
                    closeModal,
                  );
                }}
                disabled={!Boolean(formikProps.values.comments)}
              >
                Save new version
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </ComposedModal>
  );
};

interface HeaderProps {
  editVerifiedTasksEnabled: boolean;
  formikProps: FormikProps<FormProps>;
  handleSaveTaskTemplate: (
    values: any,
    resetForm: () => void,
    requestType: string,
    setRequestError?: (error: any) => void,
    closeModal?: () => void,
  ) => void;
  handleRestoreTaskTemplate: () => void;
  handleArchiveTaskTemplate: () => void;
  handleDownloadTaskTemplate: () => void;
  isActive: boolean;
  isLoading: boolean;
  isOldVersion: boolean;
  selectedTaskTemplate: Task;
  changelog: ChangeLog;
}

const Header: React.FC<HeaderProps> = ({
  editVerifiedTasksEnabled,
  selectedTaskTemplate,
  changelog,
  formikProps,
  handleSaveTaskTemplate,
  handleRestoreTaskTemplate,
  handleArchiveTaskTemplate,
  handleDownloadTaskTemplate,
  isOldVersion,
  isActive,
  isLoading,
}) => {
  const params: { team: string; name: string; version: string } = useParams();

  const TaskIcon = taskIcons.find((icon) => icon.name === selectedTaskTemplate.icon);
  const versionCount = changelog.length;
  const lastUpdated = changelog[versionCount - 1] ?? {};
  changelog.reverse();
  const canEdit = !selectedTaskTemplate?.verified || (editVerifiedTasksEnabled && selectedTaskTemplate?.verified);

  return (
    <FeatureHeader
      className={styles.featureHeader}
      footer={
        <Tabs ariaLabel="Task template views">
          <Tab
            exact
            label="Overview"
            to={
              params.team
                ? appLink.manageTasksEdit({
                    team: params.team,
                    name: selectedTaskTemplate.name,
                    version: selectedTaskTemplate.version.toString(),
                  })
                : appLink.adminTasksDetail({
                    name: selectedTaskTemplate.name,
                    version: selectedTaskTemplate.version.toString(),
                  })
            }
          />
          <Tab
            exact
            label="Editor"
            to={
              params.team
                ? appLink.manageTasksYaml({
                    team: params.team,
                    name: selectedTaskTemplate.name,
                    version: selectedTaskTemplate.version.toString(),
                  })
                : appLink.adminTasksEditor({
                    name: selectedTaskTemplate.name,
                    version: selectedTaskTemplate.version.toString(),
                  })
            }
          />
        </Tabs>
      }
      actions={
        <div className={styles.buttons}>
          <VersionSwitcher selectedTaskTemplate={selectedTaskTemplate} versionCount={versionCount} canEdit={canEdit} />
          <Button
            size="md"
            hasIconOnly
            iconDescription={
              <div className={styles.iconOnlyTooltip}>
                <p>Download this version as JSON</p>
              </div>
            }
            tooltipPosition="bottom"
            kind="ghost"
            renderIcon={Download}
            onClick={handleDownloadTaskTemplate}
          />
          {!isOldVersion && isActive && (
            <ConfirmModal
              affirmativeAction={formikProps.resetForm}
              affirmativeText="Reset changes"
              children="You are about to reset to the last save of this version, all unsaved changes will be erased. This action cannot be undone, are you sure you want to reset to the latest save?"
              title="Reset changes"
              modalTrigger={({ openModal }: ModalTriggerProps) => (
                <Button
                  size="md"
                  hasIconOnly
                  iconDescription={
                    <div className={styles.iconOnlyTooltip}>
                      <p>Restore the last save of this version</p>
                    </div>
                  }
                  tooltipPosition="bottom"
                  kind="ghost"
                  renderIcon={Reset}
                  onClick={openModal}
                />
              )}
            />
          )}
          {!isOldVersion && isActive && (
            <ConfirmModal
              affirmativeAction={handleArchiveTaskTemplate}
              affirmativeText="Archive this task"
              containerClassName={styles.archiveContainer}
              children={<ArchiveText />}
              title="Archive"
              modalTrigger={({ openModal }) => (
                <Button
                  hasIconOnly
                  iconDescription={
                    <div className={styles.iconOnlyTooltip}>
                      <p>Archive</p>
                    </div>
                  }
                  disabled={!canEdit}
                  tooltipPosition="bottom"
                  renderIcon={Archive}
                  kind="danger--ghost"
                  size="md"
                  onClick={openModal}
                />
              )}
            />
          )}
          {isOldVersion ? (
            <ConfirmModal
              affirmativeAction={() =>
                handleSaveTaskTemplate(
                  formikProps.values,
                  formikProps.resetForm,
                  TemplateRequestType.Copy,
                )
              }
              children={
                <>
                  <p className={styles.confirmModalText}>Sometimes revisiting the past is a good thing.</p>
                  <p
                    className={styles.confirmModalText}
                  >{`This action will create a new version that’s an exact copy of Version ${
                    selectedTaskTemplate.version
                  }, but it shall be named Version ${versionCount + 1}. Make sure this is what you want to do.`}</p>
                </>
              }
              affirmativeText="Copy to new version"
              title="Copy to new version"
              modalTrigger={({ openModal }: ModalTriggerProps) => (
                <TooltipHover direction="bottom" tooltipText={"Copy this version to a new version to enable editing"}>
                  <Button
                    className={styles.copyButton}
                    size="md"
                    renderIcon={Copy}
                    onClick={openModal}
                    disabled={!canEdit}
                  >
                    Copy to new version
                  </Button>
                </TooltipHover>
              )}
            />
          ) : isActive ? (
            <SaveModal
              name={selectedTaskTemplate.name}
              formikProps={formikProps}
              handleSubmit={handleSaveTaskTemplate}
              isLoading={isLoading}
              canEdit={canEdit}
            />
          ) : (
            <ConfirmModal
              affirmativeAction={handleRestoreTaskTemplate}
              children={
                <>
                  <p className={styles.confirmModalText}>
                    Restoring a task will remove it from the Archive and make it visible in the Workflow Editor, as the
                    most recent version.
                  </p>
                  <p className={styles.confirmModalText}>Are you sure you’d like to restore this task?</p>
                </>
              }
              affirmativeText="Restore this task"
              title="Restore"
              modalTrigger={({ openModal }: ModalTriggerProps) => (
                <Button className={styles.mainActionButton} size="md" renderIcon={Reset} onClick={openModal}>
                  Restore
                </Button>
              )}
            />
          )}
        </div>
      }
    >
      <p className={styles.category}>{selectedTaskTemplate.category}</p>
      <div className={styles.infoContainer}>
        {TaskIcon ? (
          <TaskIcon.Icon style={{ minWidth: "1.5rem", minHeight: "1.5rem", marginRight: "0.75rem" }} />
        ) : (
          <Bee alt={`${selectedTaskTemplate.displayName} icon`} className={styles.icon} />
        )}
        <h1 className={styles.taskName} title={selectedTaskTemplate.displayName}>
          {selectedTaskTemplate.displayName}
        </h1>
        {!isActive && (
          <Tag className={styles.archivedTag} type="gray">
            <ViewOff style={{ marginRight: "0.5rem" }} />
            Archived
          </Tag>
        )}
        <VersionHistory changelog={changelog} />
        {selectedTaskTemplate.verified ? (
          <TooltipHover
            direction="right"
            content={
              <div className={styles.tooltipContainer}>
                <strong>Verified Task</strong>
                <p style={{ marginTop: "0.5rem" }}>A task that is fully tested and verified out of the box.</p>
              </div>
            }
          >
            <Recommend style={{ marginLeft: "0.5rem" }} />
          </TooltipHover>
        ) : (
          <TooltipHover
            direction="right"
            content={
              <div className={styles.tooltipContainer}>
                <strong>Community Task</strong>
                <p style={{ marginTop: "0.5rem" }}>Contributed by a member and not validated by the Platform team.</p>
              </div>
            }
          >
            <Identification style={{ marginLeft: "0.5rem" }} />
          </TooltipHover>
        )}
      </div>
      <p className={styles.lastUpdate}>{`Version ${versionCount === 1 ? "created" : "updated"} ${moment(
        lastUpdated.date,
      ).format("MMM DD, YYYY")}`}</p>
    </FeatureHeader>
  );
};

export default Header;
