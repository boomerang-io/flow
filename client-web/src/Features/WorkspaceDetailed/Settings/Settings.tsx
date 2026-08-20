import React, { useState } from "react";
import { Helmet } from "react-helmet";
import { useMutation, useQueryClient } from "react-query";
import { useNavigate, useRevalidator } from "react-router-dom";
import { InlineNotification, Button } from "@carbon/react";
import {
  ConfirmModal,
  ComposedModal,
  notify,
  ToastNotification,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import UpdateWorkspaceName from "./UpdateWorkspaceName";
import {
  StructuredListWrapper,
  StructuredListHead,
  StructuredListRow,
  StructuredListCell,
  StructuredListBody,
} from "@carbon/react";
import { Edit, Close, TrashCan, Add, Copy } from "@carbon/react/icons";
import CopyToClipboard from "react-copy-to-clipboard";
import sortBy from "lodash/sortBy";
import { FlowWorkspace, ModalTriggerProps } from "Types";
import LabelModal from "Components/LabelModal";
import { appLink } from "Config/appConfig";
import styles from "./Settings.module.scss";
import { resolver, serviceUrl } from "Config/servicesConfig";

interface Label {
  key: string;
  value: string;
}

export default function Settings({ workspace, canEdit }: { workspace: FlowWorkspace; canEdit: boolean }) {
  const [copyTokenText, setCopyTokenText] = useState("Copy");
  const queryClient = useQueryClient();
  // The user profile (workspace membership list) is now root-loader-driven (Features/App/App.tsx)
  // rather than a react-query cache entry, so deleting a workspace here has to re-run that loader
  // via revalidate() - queryClient.invalidateQueries(getUserProfile()) would be a silent no-op.
  const revalidator = useRevalidator();
  const navigate = useNavigate();

  const patchWorkspaceMutator = useMutation(resolver.patchUpdateWorkspace);
  const deleteWorkspaceMutator = useMutation(resolver.deleteWorkspace);

  const handleDeleteWorkspace = async () => {
    try {
      await deleteWorkspaceMutator.mutateAsync({ workspace: workspace.name });
      revalidator.revalidate();
      navigate(appLink.home());
      notify(
        <ToastNotification
          title="Delete Workspace"
          subtitle={`Request to delete '${workspace.displayName}' was successful`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  const handleAddLabel = async (value: Label) => {
    const newLabels = [...workspaceLabels, value];
    const newLabelsRecord = newLabels.reduce(
      (acc, label) => {
        acc[label.key] = label.value;
        return acc;
      },
      {} as Record<string, string>,
    );

    try {
      await patchWorkspaceMutator.mutateAsync({ workspace: workspace.name, body: { labels: newLabelsRecord } });
      queryClient.invalidateQueries(serviceUrl.resourceWorkspace({ workspace: workspace.name }));
      notify(
        <ToastNotification
          title="Add Label"
          subtitle={`Added label to ${workspace.displayName} successfully`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  const handleRemoveLabel = async (value: Label) => {
    const newLabels = workspaceLabels.filter((label) => label.key !== value.key);
    const newLabelsRecord = newLabels.reduce(
      (acc, label) => {
        acc[label.key] = label.value;
        return acc;
      },
      {} as Record<string, string>,
    );

    try {
      await patchWorkspaceMutator.mutateAsync({ workspace: workspace.name, body: { labels: newLabelsRecord } });
      queryClient.invalidateQueries(serviceUrl.resourceWorkspace({ workspace: workspace.name }));
      notify(
        <ToastNotification
          title="Remove Workspace"
          subtitle={`Request to close ${workspace.displayName} successful`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  // Convert Record/Map of Labels to Array of Label Object
  const workspaceLabels = workspace.labels ? Object.entries(workspace.labels).map(([key, value]) => ({ key, value })) : [];
  const labelsKeys = workspace.labels ? Object.keys(workspace.labels) : [];

  return (
    <section aria-label="Workspace Settings" className={styles.settingsContainer}>
      <Helmet>
        <title>{`Settings - ${workspace.displayName}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The workspace may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
            scenes."
          />
        </section>
      ) : null}
      <p className={styles.settingsDescription}>
        Configurable settings for this Workspace.
      </p>
      <SettingSection
        title="Basic details"
        editModal={
          <ComposedModal
            composedModalProps={{
              containerClassName: styles.workspaceNameModalContainer,
            }}
            modalHeaderProps={{
              title: "Change workspace name",
              //   subtitle:
              //     "Try to keep it concise to avoid truncation in the sidebar. You must make sure the name is valid before it can be updated.",
            }}
            modalTrigger={({ openModal }) => (
              <button
                disabled={!canEdit}
                className={styles.workspaceEditIcon}
                onClick={openModal}
                data-testid="open-change-name-modal"
              >
                <Edit />
              </button>
            )}
          >
            {({ closeModal }) => <UpdateWorkspaceName closeModal={closeModal} workspace={workspace} />}
          </ComposedModal>
        }
      >
        <dl className={styles.detailedListContainer}>
          <div className={styles.detailedListGrid}>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Display Name</dt>
              <dd className={styles.detailedListDescription}>{workspace.displayName}</dd>
            </div>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Unique Identifier Name</dt>
              <dd className={styles.detailedListDescription}>
                {workspace.name}
                <TooltipHover direction="top" content={copyTokenText} hideOnClick={false}>
                  <button
                    className={styles.copyButton}
                    onClick={() => setCopyTokenText("Copied")}
                    onMouseLeave={() => setCopyTokenText("Copy")}
                    type="button"
                  >
                    <CopyToClipboard text={workspace.name}>
                      <Copy fill={"#0072C3"} className={styles.actionIcon} aria-label="Copy" />
                    </CopyToClipboard>
                  </button>
                </TooltipHover>
              </dd>
            </div>
          </div>
        </dl>
      </SettingSection>
      <SettingSection title="Labels">
        <dl className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>Create custom labels can be useful when querying the API.</p>
          <StructuredListWrapper
            className={styles.structuredListWrapper}
            ariaLabel="Structured list"
            isCondensed={true}
          >
            <StructuredListHead>
              <StructuredListRow head>
                <StructuredListCell head>Key</StructuredListCell>
                <StructuredListCell head>Value</StructuredListCell>
                <StructuredListCell head />
              </StructuredListRow>
            </StructuredListHead>
            <StructuredListBody>
              {sortBy(workspaceLabels, "key").map((label: Label) => {
                //const labelIndex = workspaceLabels.findIndex((labelFromList) => labelFromList.key === label.key);
                return (
                  <StructuredListRow key={label.key}>
                    <StructuredListCell className={styles.labelKeyCell}>{label.key}</StructuredListCell>
                    <StructuredListCell>{label.value}</StructuredListCell>
                    {canEdit && (
                      <>
                        <StructuredListCell>
                          <LabelModal
                            action={handleAddLabel}
                            isEdit
                            labelsKeys={labelsKeys.filter((labelKey) => labelKey !== label.key)}
                            selectedLabel={label}
                            modalTrigger={({ openModal }: ModalTriggerProps) => (
                              <Button
                                kind="ghost"
                                iconDescription="edit label"
                                renderIcon={Edit}
                                size="sm"
                                onClick={openModal}
                              >
                                Edit
                              </Button>
                            )}
                          />
                          <ConfirmModal
                            modalTrigger={({ openModal }) => (
                              <Button
                                kind="danger--ghost"
                                iconDescription="delete label"
                                renderIcon={TrashCan}
                                size="sm"
                                onClick={openModal}
                                data-testid={`delete-token-button-${label}`}
                              />
                            )}
                            affirmativeAction={() => handleRemoveLabel(label)}
                            affirmativeButtonProps={{ kind: "danger" }}
                            affirmativeText="Yes"
                            negativeText="No"
                            title={`Are you sure?`}
                          >
                            Delete
                          </ConfirmModal>
                        </StructuredListCell>
                      </>
                    )}
                  </StructuredListRow>
                );
              })}
              <LabelModal
                action={handleAddLabel}
                labelsKeys={labelsKeys}
                modalTrigger={({ openModal }: ModalTriggerProps) => (
                  <Button kind="ghost" iconDescription="add a new label" renderIcon={Add} size="md" onClick={openModal} disabled={!canEdit}>
                    Add a new label
                  </Button>
                )}
              />
            </StructuredListBody>
          </StructuredListWrapper>
        </dl>
        {/* <CreateToken getTokensUrl={getTokensUrl} principal={user.id} type="user" /> */}
      </SettingSection>
      <SettingSection title="Delete Workspace">
        <div className={styles.buttonWithMessageContainer}>
          <p className={styles.buttonHelperText}>
            Done with your work here? Deleting this workspace will permanently remove the workspace, including its Workflows, Task Templates, Runs, and Tokens. Its members will no longer be able to access this workspace. This action is irreversible - continue with caution.
          </p>
          <ConfirmModal
            affirmativeAction={() => handleDeleteWorkspace()}
            affirmativeButtonProps={{ kind: "danger", "data-testid": "confirm-close-workspace" }}
            title={`Delete ${workspace.displayName}?`}
            negativeText="Cancel"
            affirmativeText="Delete Workspace"
            modalTrigger={({ openModal }) => (
              <Button
                disabled={!canEdit}
                iconDescription="Close"
                kind="danger--ghost"
                onClick={openModal}
                renderIcon={Close}
                size="md"
                data-testid="close-workspace"
              >
                Delete Workspace
              </Button>
            )}
          >
            This workspace will be permanently deleted, along with all of its Workflows, Task Templates, Runs, and Tokens. This action is irreversible - are you sure you want to do this?
          </ConfirmModal>
        </div>
      </SettingSection>
    </section>
  );
}

interface SettingSectionProps {
  children: React.ReactNode;
  description?: React.ReactNode;
  editModal?: React.ReactNode;
  title: string;
}

function SettingSection({ children, description, editModal, title }: SettingSectionProps) {
  return (
    <section className={styles.sectionContainer}>
      <div className={styles.sectionHeader}>
        <h1 className={styles.sectionTitle}>{title}</h1>
        {editModal}
      </div>
      {description ? <p className={styles.sectionDescription}>{description}</p> : null}
      {children}
    </section>
  );
}
