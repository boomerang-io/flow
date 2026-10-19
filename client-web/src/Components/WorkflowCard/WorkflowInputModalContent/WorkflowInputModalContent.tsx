import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { DynamicFormik, ModalFlowForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { REFERENCE_HELPER } from "Components/SecretInput";
import { InputProperty, InputType, PASSWORD_CONSTANT, REDACTED } from "Constants";
import { DataDrivenInput } from "Types";
import { toDynamicInputs } from "Utils/paramsHelper";
import styles from "./workflowInputModalContent.module.scss";

interface WorkflowInputModalContentProps {
  closeModal: () => void;
  executeError: any;
  executeWorkflow: (closeModal: () => void, redirect: boolean, properties: {}) => Promise<void>;
  inputs: Array<DataDrivenInput>;
  isExecuting: boolean;
  errorMessage: { title: string; message: string } | null;
}

const WorkflowInputModalContent: React.FC<WorkflowInputModalContentProps> = ({
  closeModal,
  executeError,
  executeWorkflow,
  inputs,
  isExecuting,
  errorMessage,
}) => {
  const [isRedirectEnabled, setIsRedirectEnabled] = React.useState(false);

  // A secret with a stored default reads as the marker. The field starts empty rather than holding
  // the marker, and a blank is sent as no value, so the stored default applies.
  const secureInputs = inputs.map((input: DataDrivenInput) => {
    /* @ts-ignore-next-line */
    if (input.type === InputType.Password && (input?.hiddenValue || input.default === REDACTED)) {
      return {
        ...input,
        key: input.name,
        required: false,
        helperText: `Leave blank to use the stored default. ${REFERENCE_HELPER}`,
        placeholder: PASSWORD_CONSTANT,
        defaultValue: "",
      };
    } else return { ...input, defaultValue: input.default, key: input.name };
  });

  return (
    <DynamicFormik
      allowCustomPropertySyntax
      validateOnMount
      inputs={toDynamicInputs(secureInputs)}
      toggleProps={() => ({
        orientation: "vertical",
      })}
      onSubmit={(values: any) => {
        executeWorkflow(closeModal, isRedirectEnabled, values);
      }}
    >
      {({ inputs, formikProps }: { inputs: React.ReactNode[]; formikProps: any }) => (
        <ModalFlowForm className={styles.container}>
          <ModalBody aria-label="inputs">
            {inputs}
            {executeError && (
              <InlineNotification
                lowContrast
                kind="error"
                title={errorMessage?.title}
                subtitle={errorMessage?.message}
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button kind="secondary" onClick={closeModal} type="button">
              Cancel
            </Button>
            {isExecuting ? (
              <Button disabled style={{ flex: "0 1 107.5%" }}>
                Running...
              </Button>
            ) : (
              <>
                <Button
                  disabled={!formikProps.isValid}
                  onClick={(e: React.SyntheticEvent) => {
                    formikProps.handleSubmit();
                  }}
                  type="button"
                >
                  Run
                </Button>
                <Button
                  disabled={!formikProps.isValid}
                  onClick={(e: React.SyntheticEvent) => {
                    setIsRedirectEnabled(true);
                    formikProps.handleSubmit();
                  }}
                  type="button"
                >
                  Run and View
                </Button>
              </>
            )}
          </ModalFooter>
        </ModalFlowForm>
      )}
    </DynamicFormik>
  );
};

export default WorkflowInputModalContent;
