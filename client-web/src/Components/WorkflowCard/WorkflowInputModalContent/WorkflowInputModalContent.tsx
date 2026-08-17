import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { DynamicFormik, ModalFlowForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { InputProperty, InputType, PASSWORD_CONSTANT } from "Constants";
import { DataDrivenInput } from "Types";
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

  //edit inputs to handle secure values
  const secureInputs = inputs.map((input: DataDrivenInput) => {
    /* @ts-ignore-next-line */
    if (input.type === InputType.Password && input?.hiddenValue) {
      //if the input type is secure and there is a default value we are going to manipulate the object
      return {
        //allow the user to submit null
        ...input,
        key: input.name,
        required: false,
        helperText: "To use your secure default value, leave this input blank",
        placeholder: PASSWORD_CONSTANT,
        defaultValue: input.default,
      };
    } else return { ...input, defaultValue: input.default, key: input.name };
  });

  return (
    <DynamicFormik
      allowCustomPropertySyntax
      validateOnMount
      inputs={secureInputs}
      toggleProps={() => ({
        orientation: "vertical",
      })}
      onSubmit={(values: any) => {
        executeWorkflow(closeModal, isRedirectEnabled, values);
      }}
    >
      {({ inputs, formikProps }: { inputs: JSX.Element; formikProps: any }) => (
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
