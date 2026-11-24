import React from "react";
import { Formik } from "formik";
import { useFetcher, useNavigate } from "react-router-dom";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  ConfirmModal,
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { checkWorkspaceNameAvailable } from "Config/resourceRoutes";
import kebabcase from "lodash/kebabCase";
import * as Yup from "yup";
import { appLink } from "Config/appConfig";
import styles from "./UpdateWorkspaceName.module.scss";
import { FlowWorkspace } from "Types";
import type { SettingsActionResult } from "../Settings";
import { WorkspaceIntent } from "../../WorkspaceDetailed";
import { isActionError } from "Utils/actionResult";

interface UpdateWorkspaceNameProps {
  closeModal: () => void;
  workspace: FlowWorkspace;
}

type BasicDetails = { name: string; displayName: string };

// The workspace name as the server stores it: lower-case letters, numbers and hyphens.
function toName(value: string) {
  return kebabcase(value.replace(`'`, "-"));
}

/**
 * The workspace's display name and its name, edited apart. The display name changes on its own;
 * the name is its key in URLs and tokens, so a change to it is asked about before it is sent.
 */
const UpdateWorkspaceName: React.FC<UpdateWorkspaceNameProps> = ({ closeModal, workspace }) => {
  const navigate = useNavigate();
  // Both changes post to the Settings tab's route action (see ../Settings).
  const fetcher = useFetcher<SettingsActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const failed = Boolean(fetcher.data && isActionError(fetcher.data) && fetcher.data.intent === WorkspaceIntent.Rename);
  // A rename waiting on the confirmation.
  const [pendingRename, setPendingRename] = React.useState<BasicDetails | null>(null);

  // The name-availability probe cannot move to the route action: it runs inside Yup's async
  // `test`, which needs a promise to await per keystroke, and fetcher.submit is fire-and-forget
  // with no awaitable result. So it fetches the same-origin /res/workspace/validate-name
  // resource route (Config/resourceRoutes.ts) instead of calling /api directly - the SSR server
  // makes the service-core call. Counter, not boolean: rapid keystrokes overlap probes.
  const [pendingProbes, setPendingProbes] = React.useState(0);
  const isValidating = pendingProbes > 0;

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== WorkspaceIntent.Rename) {
      return;
    }
    if (!isActionError(fetcher.data)) {
      // The name is part of the URL, so a rename has to move the router to the new one; the
      // parent loader then re-fetches under the new :workspace param.
      navigate(appLink.manageWorkspaceSettings({ workspace: String(fetcher.data.detail) }));
      notify(
        <ToastNotification kind="success" title="Update Workspace Settings" subtitle="Workspace settings successfully updated" />
      );
      closeModal();
    } else {
      notify(<ToastNotification kind="error" subtitle="Failed to update workspace settings" title="Something's Wrong" />);
    }
  }, [fetcher.state, fetcher.data, navigate, closeModal]);

  const send = (details: BasicDetails) => {
    fetcher.submit({ intent: WorkspaceIntent.Rename, ...details }, { method: "post" });
  };

  // The name as entered, unless it is the one the workspace has: a stored name is sent back as it
  // is, so a display-name change never renames.
  const nameOf = (value: string) => (value === workspace.name ? workspace.name : toName(value));

  const save = (values: BasicDetails) => {
    const details = { name: nameOf(values.name), displayName: values.displayName };
    if (details.name === workspace.name) {
      send(details);
    } else {
      setPendingRename(details);
    }
  };

  let buttonText = "Save";
  if (isSubmitting) {
    buttonText = "Saving...";
  } else if (failed) {
    buttonText = "Try again";
  } else if (isValidating) {
    buttonText = "Validating...";
  }

  return (
    <>
      <Formik
        initialValues={{ name: workspace.name, displayName: workspace.displayName }}
        onSubmit={save}
        validationSchema={Yup.object().shape({
          displayName: Yup.string()
            .required("Enter a display name")
            .max(100, "Enter a display name that is at most 100 characters in length"),
          name: Yup.string()
            .required("Enter a workspace name")
            .max(100, "Enter workspace name that is at most 100 characters in length")
            .test("isUnique", "TAKEN", async (value) => {
              if (!value || nameOf(value) === workspace.name) {
                return true;
              }
              setPendingProbes((count) => count + 1);
              try {
                // Never rejects: unavailable/unreachable both read as `false` -> TAKEN.
                return await checkWorkspaceNameAvailable(toName(value));
              } finally {
                setPendingProbes((count) => count - 1);
              }
            }),
        })}
      >
        {(formikProps) => {
          const { values, setFieldValue, handleSubmit, errors, touched } = formikProps;
          const renaming = Boolean(values.name) && nameOf(values.name) !== workspace.name;
          return (
            <ModalFlowForm>
              <ModalBody>
                <div className={styles.modalInputContainer}>
                  {isSubmitting && <Loading />}
                  <TextInput
                    id="workspace-update-display-name-id"
                    data-testid="text-input-workspace-display-name"
                    labelText="Display Name"
                    helperText="What people see for this workspace. Change it whenever you like."
                    value={values.displayName}
                    onChange={(value: React.ChangeEvent<HTMLInputElement>) => {
                      setFieldValue("displayName", value.target.value);
                    }}
                    invalid={Boolean(errors.displayName && !touched.displayName)}
                    invalidText={errors.displayName}
                  />
                  <TextInput
                    id="workspace-update-name-id"
                    data-testid="text-input-workspace-name"
                    labelText="Name"
                    helperText="Used in this workspace's URLs and tokens."
                    value={values.name}
                    onChange={(value: React.ChangeEvent<HTMLInputElement>) => {
                      setFieldValue("name", value.target.value);
                    }}
                    invalid={Boolean(errors.name && !touched.name)}
                    invalidText={
                      errors.name === "TAKEN" ? `Please try again, the name '${values.name}' is unavailable.` : errors.name
                    }
                  />
                  {failed && (
                    <InlineNotification
                      lowContrast
                      kind="error"
                      title="Update failed!"
                      subtitle="Give it another go or try again later."
                    />
                  )}
                  {renaming && (
                    <div className={styles.text}>
                      <p>
                        The new name will be "<b>{nameOf(values.name)}</b>", adjusted to lower-case letters, numbers and
                        hyphens.
                      </p>
                    </div>
                  )}
                </div>
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" type="button" onClick={closeModal}>
                  Cancel
                </Button>
                <Button
                  disabled={Boolean(errors.name || errors.displayName || isSubmitting || isValidating)}
                  onClick={() => handleSubmit()}
                  data-testid="save-workspace-name"
                >
                  {buttonText}
                </Button>
              </ModalFooter>
            </ModalFlowForm>
          );
        }}
      </Formik>
      {pendingRename && (
        <ConfirmModal
          affirmativeAction={() => {
            send(pendingRename);
            setPendingRename(null);
          }}
          affirmativeText="Rename"
          isOpen
          negativeAction={() => setPendingRename(null)}
          negativeText="Cancel"
          onCloseModal={() => setPendingRename(null)}
          title="Rename this workspace?"
        >
          {`Its URLs change to use the new name, "${pendingRename.name}". Anything that uses the old name stops working: webhook and event URLs, bookmarked links, and workspace tokens issued before 5.0.1. Tokens issued since then keep working.`}
        </ConfirmModal>
      )}
    </>
  );
};

export default UpdateWorkspaceName;
