import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm, TextInput, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import kebabcase from "lodash/kebabCase";
import { useMutation } from "react-query";
import * as Yup from "yup";
import { resolver } from "Config/servicesConfig";
import styles from "./WorkspaceCreateContent.module.scss";

interface WorkspaceCreateContentProps {
  closeModal: () => void;
  createWorkspace: (values: { name: string | undefined }, success_fn: () => void) => void;
  isLoading: boolean;
  isError: boolean;
}

export default function WorkspaceCreateContent({ closeModal, createWorkspace, isLoading, isError }: WorkspaceCreateContentProps) {
  const validateWorkspaceNameMutator = useMutation(resolver.postWorkspaceValidateName);

  let buttonText = "Create";
  if (isLoading) {
    buttonText = "Creating...";
  } else if (isError) {
    buttonText = "Try again";
  } else if (validateWorkspaceNameMutator.isLoading) {
    buttonText = "Validating...";
  }

  return (
    <Formik
      initialValues={{
        name: "",
      }}
      onSubmit={(values) => createWorkspace(values, closeModal)}
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
        const { values, setFieldValue, handleSubmit, errors, touched, dirty } = formikProps;
        return (
          <ModalForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                {isLoading && <Loading />}
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
                {isError && (
                  <InlineNotification
                    lowContrast
                    kind="error"
                    title="Create workspace failed!"
                    subtitle="Give it another go or try again later."
                  />
                )}
                <div className={styles.text}>
                  {values.name ? (
                    <p>
                      Your unique workspace name identifier will be "
                      <b>{kebabcase(values ? values.name.replace(`'`, "-") : "")}</b>", which has been adjusted to
                      remove spaces and special characters.
                    </p>
                  ) : (
                    <p>Your unique workspace name identifier will be adjusted to remove spaces and special characters.</p>
                  )}
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                disabled={!dirty || errors.name || isLoading || validateWorkspaceNameMutator.isLoading}
                onClick={handleSubmit}
                data-testid="save-workspace-name"
              >
                {buttonText}
              </Button>
            </ModalFooter>
          </ModalForm>
        );
      }}
    </Formik>
  );
}
