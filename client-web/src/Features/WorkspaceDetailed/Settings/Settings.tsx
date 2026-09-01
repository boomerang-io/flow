import React, { useState } from "react";
import { Helmet } from "react-helmet";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useFetcher, useNavigate } from "react-router-dom";
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
import { ModalTriggerProps } from "Types";
import { useWorkspaceDetailedContext, WorkspaceIntent } from "../WorkspaceDetailed";
import LabelModal from "Components/LabelModal";
import { appLink } from "Config/appConfig";
import styles from "./Settings.module.scss";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";

interface Label {
  key: string;
  value: string;
}

// Route module for the Settings tab (app/routes/manageWorkspaceSettings.tsx). Every write on this
// tab - label add/remove here, and the rename in ./UpdateWorkspaceName - posts to this one
// intent-keyed action via useFetcher. Its completion revalidates both the parent Manage Workspace
// loader (the workspace record everything here displays) and the root loader (the user profile's
// workspace membership list, which a delete changes).
//
// The one call NOT here is the rename modal's name-availability probe: it runs inside a Yup
// async validation test, which needs a promise it can await per keystroke, and fetcher.submit is
// fire-and-forget. It stays a direct browser call - see ./UpdateWorkspaceName.
//
// The intent names are the workspace-scoped ones exported by ../WorkspaceDetailed
// (`renameWorkspace`/`deleteWorkspace`/`updateWorkspaceLabels`), not bare verbs: that route's
// shouldRevalidate keys off two of them, and the Approver Groups and Tokens tabs submit their own
// deletes to the same matched route tree.
export type SettingsActionResult =
  | {
      intent: (typeof WorkspaceIntent)[keyof typeof WorkspaceIntent];
      /**
       * "updateWorkspaceLabels": which of the two toasts to raise. "renameWorkspace": the new
       * workspace slug to navigate to.
       */
      detail?: string;
    }
  | ({ intent: (typeof WorkspaceIntent)[keyof typeof WorkspaceIntent]; detail?: string } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === WorkspaceIntent.Delete) {
    try {
      await serverFetch(request).delete(serviceUrl.resourceWorkspace({ workspace }));
      return { intent: WorkspaceIntent.Delete };
    } catch (error) {
      return actionError({
        intent: WorkspaceIntent.Delete,
        error: formatErrorMessage({ error, defaultMessage: "Request to delete workspace failed" }),
      });
    }
  }

  if (intent === WorkspaceIntent.Rename) {
    const name = String(formData.get("name"));
    const displayName = String(formData.get("displayName"));
    try {
      await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { name, displayName });
      return { intent: WorkspaceIntent.Rename, detail: name };
    } catch (error) {
      return actionError({
        intent: WorkspaceIntent.Rename,
        error: formatErrorMessage({ error, defaultMessage: "Failed to update workspace settings" }),
      });
    }
  }

  // Labels: the whole label record is sent, add and remove alike - the caller computes it.
  const labels = JSON.parse(String(formData.get("labels")));
  const operation = String(formData.get("operation"));
  try {
    await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { labels });
    return { intent: WorkspaceIntent.UpdateLabels, detail: operation };
  } catch (error) {
    return actionError({
      intent: WorkspaceIntent.UpdateLabels,
      detail: operation,
      error: formatErrorMessage({ error, defaultMessage: "Request to update labels failed" }),
    });
  }
}

// Settings tab of /:workspace/manage (app/routes/manageWorkspaceSettings.tsx). The workspace and
// `canEdit` arrive from the parent layout route's <Outlet context> rather than as props.
export default function Settings() {
  const { workspace, canEdit } = useWorkspaceDetailedContext();
  const [copyTokenText, setCopyTokenText] = useState("Copy");
  const navigate = useNavigate();
  // Posts to this file's `action`. Settling it revalidates every matched loader - the parent
  // Manage Workspace loader (the workspace record shown here) and the root loader (the user
  // profile's workspace list, which a delete changes) - so neither needs a manual revalidate().
  const fetcher = useFetcher<SettingsActionResult>();

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (isActionError(fetcher.data)) {
      // Matches the previous handlers, which all swallowed their errors (`// noop`).
      return;
    }
    const { intent, detail } = fetcher.data;
    if (intent === WorkspaceIntent.Delete) {
      navigate(appLink.home());
      notify(
        <ToastNotification
          title="Delete Workspace"
          subtitle={`Request to delete '${workspace.displayName}' was successful`}
          kind="success"
        />,
      );
      return;
    }
    if (intent === WorkspaceIntent.UpdateLabels) {
      notify(
        detail === "add" ? (
          <ToastNotification
            title="Add Label"
            subtitle={`Added label to ${workspace.displayName} successfully`}
            kind="success"
          />
        ) : (
          <ToastNotification
            title="Remove Workspace"
            subtitle={`Request to close ${workspace.displayName} successful`}
            kind="success"
          />
        ),
      );
    }
    // The rename is handled in ./UpdateWorkspaceName, which owns its own fetcher.
  }, [fetcher.state, fetcher.data, navigate, workspace.displayName]);

  const submitLabels = (labels: Array<Label>, operation: "add" | "remove") => {
    const labelsRecord = labels.reduce(
      (acc, label) => {
        acc[label.key] = label.value;
        return acc;
      },
      {} as Record<string, string>,
    );
    fetcher.submit(
      { intent: WorkspaceIntent.UpdateLabels, operation, labels: JSON.stringify(labelsRecord) },
      { method: "post" },
    );
  };

  const handleDeleteWorkspace = () => {
    fetcher.submit({ intent: WorkspaceIntent.Delete }, { method: "post" });
  };

  const handleAddLabel = (value: Label) => submitLabels([...workspaceLabels, value], "add");

  const handleRemoveLabel = (value: Label) =>
    submitLabels(
      workspaceLabels.filter((label) => label.key !== value.key),
      "remove",
    );

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
