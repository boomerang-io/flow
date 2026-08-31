import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm, TextInput, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import kebabcase from "lodash/kebabCase";
import * as Yup from "yup";
import { checkWorkspaceNameAvailable } from "Config/resourceRoutes";
import styles from "./WorkspaceCreateContent.module.scss";

interface WorkspaceCreateContentProps {
  closeModal: () => void;
  createWorkspace: (values: { name: string | undefined }, success_fn: () => void) => void;
  isLoading: boolean;
  isError: boolean;
}

export default function WorkspaceCreateContent({ closeModal, createWorkspace, isLoading, isError }: WorkspaceCreateContentProps) {
  // The name-availability probe runs inside Yup's async `test` below, which needs an awaitable
  // promise per change - a fetcher cannot provide one, so it is a same-origin fetch of the
  // /res/workspace/validate-name resource route (Config/resourceRoutes.ts), the SSR server
  // making the actual service-core call. A counter rather than a boolean because rapid changes
  // overlap probes; the button reads "Validating..." until the last one settles.
  const [pendingProbes, setPendingProbes] = React.useState(0);
  const isValidating = pendingProbes > 0;

  let buttonText = "Create";
  if (isLoading) {
    buttonText = "Creating...";
  } else if (isError) {
    buttonText = "Try again";
  } else if (isValidating) {
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
            if (!value) {
              return true;
            }
            setPendingProbes((count) => count + 1);
            try {
              // Never rejects: unavailable/unreachable both read as `false` -> TAKEN.
              return await checkWorkspaceNameAvailable(kebabcase(value.replace(`'`, "-")));
            } finally {
              setPendingProbes((count) => count - 1);
            }
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
                disabled={!dirty || Boolean(errors.name) || isLoading || isValidating}
                onClick={() => handleSubmit()}
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
