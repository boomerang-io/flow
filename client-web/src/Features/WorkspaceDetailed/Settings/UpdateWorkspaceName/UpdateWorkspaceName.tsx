import React from "react";
import { useQueryClient, useMutation } from "react-query";
import { Formik } from "formik";
import { useHistory } from "react-router-dom";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver, serviceUrl } from "Config/servicesConfig";
import kebabcase from "lodash/kebabCase";
import * as Yup from "yup";
import { appLink } from "Config/appConfig";
import styles from "./UpdateWorkspaceName.module.scss";
import { FlowWorkspace } from "Types";

interface UpdateWorkspaceNameProps {
  closeModal: () => void;
  workspace: FlowWorkspace;
}

const UpdateWorkspaceName: React.FC<UpdateWorkspaceNameProps> = ({ closeModal, workspace }) => {
  const queryClient = useQueryClient();
  const history = useHistory();

  const validateWorkspaceNameMutator = useMutation(resolver.postWorkspaceValidateName);
  const updateWorkspaceMutator = useMutation(resolver.patchUpdateWorkspace);
  const updateWorkspaceName = async (values: { name: string }) => {
    const newWorkspaceName = kebabcase(values.name?.replace(`'`, "-"));
    try {
      await updateWorkspaceMutator.mutateAsync({
        workspace: workspace.name,
        body: {
          name: newWorkspaceName,
          displayName: values.name,
        },
      });
      queryClient.invalidateQueries(serviceUrl.resourceWorkspace({ workspace: newWorkspaceName }));
      history.push(appLink.manageWorkspaceSettings({ workspace: newWorkspaceName }));
      notify(
        <ToastNotification kind="success" title="Update Workspace Settings" subtitle="Workspace settings successfully updated" />
      );
      closeModal();
    } catch (error) {
      notify(<ToastNotification kind="error" subtitle="Failed to update workspace settings" title="Something's Wrong" />);
    }
  };

  let buttonText = "Save";
  if (updateWorkspaceMutator.isLoading) {
    buttonText = "Saving...";
  } else if (updateWorkspaceMutator.isError) {
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
                {updateWorkspaceMutator.isLoading && <Loading />}
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
                {updateWorkspaceMutator.error && (
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
                    updateWorkspaceMutator.isLoading ||
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
