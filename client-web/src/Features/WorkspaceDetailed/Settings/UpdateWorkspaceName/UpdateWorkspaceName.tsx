import React from "react";
import { useMutation } from "react-query";
import { Formik } from "formik";
import { useFetcher, useNavigate } from "react-router-dom";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver } from "Config/servicesConfig";
import kebabcase from "lodash/kebabCase";
import * as Yup from "yup";
import { appLink } from "Config/appConfig";
import styles from "./UpdateWorkspaceName.module.scss";
import { FlowWorkspace } from "Types";
import type { SettingsActionResult } from "../Settings";

interface UpdateWorkspaceNameProps {
  closeModal: () => void;
  workspace: FlowWorkspace;
}

const UpdateWorkspaceName: React.FC<UpdateWorkspaceNameProps> = ({ closeModal, workspace }) => {
  const navigate = useNavigate();
  // The rename itself posts to the Settings tab's route action (see ../Settings).
  const fetcher = useFetcher<SettingsActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const failed = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent === "rename");

  // The name-availability probe stays a direct browser call rather than moving to the route
  // action: it runs inside Yup's async `test`, which needs a promise to await per keystroke, and
  // fetcher.submit is fire-and-forget with no awaitable result. It is a validation probe, not the
  // mutation - the mutation above is what moved.
  const validateWorkspaceNameMutator = useMutation(resolver.postWorkspaceValidateName);

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "rename") {
      return;
    }
    if (fetcher.data.ok) {
      // The slug is part of the URL, so a rename has to move the router to the new one; the
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

  const updateWorkspaceName = (values: { name: string }) => {
    fetcher.submit(
      {
        intent: "rename",
        name: kebabcase(values.name?.replace(`'`, "-")),
        displayName: values.name,
      },
      { method: "post" },
    );
  };

  let buttonText = "Save";
  if (isSubmitting) {
    buttonText = "Saving...";
  } else if (failed) {
    buttonText = "Try again";
  } else if (validateWorkspaceNameMutator.isLoading) {
    buttonText = "Validating...";
  }

  //TODO - update the error message to include the value of the Text Input
  //TODO - update to not error on current workspace name
  return (
    <Formik
      initialValues={{
        name: workspace.displayName,
      }}
      onSubmit={updateWorkspaceName}
      validationSchema={Yup.object().shape({
        name: Yup.string()
          .required("Enter a workspace name")
          .max(100, "Enter workspace name that is at most 100 characters in length")
          .test("isUnique", "TAKEN", async (value) => {
            let isValid = true;
            if (value) {
              try {
                await validateWorkspaceNameMutator.mutateAsync({ body: { name: kebabcase(value.replace(`'`, "-")) } });
              } catch (e) {
                console.error(e);
                isValid = false;
              }
            }
            // Need to return promise for yup to do async validation
            return Promise.resolve(isValid);
          }),
      })}
    >
      {(formikProps) => {
        const { values, setFieldValue, handleSubmit, errors, touched } = formikProps;
        return (
          <ModalFlowForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                {isSubmitting && <Loading />}
                <TextInput
                  id="workspace-update-name-id"
                  data-testid="text-input-workspace-name"
                  labelText="Display Name"
                  helperText="The display name of your workspace must make a unique name identifier."
                  value={values.name}
                  onChange={(value: React.ChangeEvent<HTMLInputElement>) => {
                    setFieldValue("name", value.target.value);
                  }}
                  invalid={Boolean(errors.name && !touched.name)}
                  invalidText={
                    errors.name === "TAKEN"
                      ? `Please try again, the name '${values.name}' is unavailable.`
                      : errors.name
                  }
                />
                {failed && (
                  <InlineNotification
                    lowContrast
                    kind="error"
                    title="Name changed failed!"
                    subtitle="Give it another go or try again later."
                  />
                )}
                <div className={styles.text}>
                  {values.name ? (
                    <p>
                      Your updated unique workspace name identifier will be "
                      <b>{kebabcase(values ? values.name.replace(`'`, "-") : "")}</b>", which has been adjusted to
                      remove spaces and special characters.
                    </p>
                  ) : (
                    <p>
                      Your updated unique workspace name identifier will be adjusted to remove spaces and special characters.
                    </p>
                  )}
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                disabled={Boolean(
                  errors.name ||
                    isSubmitting ||
                    validateWorkspaceNameMutator.error ||
                    validateWorkspaceNameMutator.isLoading,
                )}
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
  );
};

export default UpdateWorkspaceName;
