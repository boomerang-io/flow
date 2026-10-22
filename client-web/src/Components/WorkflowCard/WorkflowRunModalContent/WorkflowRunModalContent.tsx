import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
interface WorkflowRunModalContentProps {
  closeModal: () => void;
  executeError: any;
  executeWorkflow: (closeModal: () => void, redirect: boolean) => void;
  isExecuting: boolean;
  errorMessage: { title: string; message: string } | null;
}

const WorkflowRunModalContent: React.FC<WorkflowRunModalContentProps> = ({
  closeModal,
  executeError,
  executeWorkflow,
  isExecuting,
  errorMessage,
}) => {
  return (
    <ModalForm>
      {executeError && (
        <ModalBody>
          <InlineNotification lowContrast kind="error" title={errorMessage?.title} subtitle={errorMessage?.message} />
        </ModalBody>
      )}
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Cancel
        </Button>
        {isExecuting ? (
          <Button disabled style={{ flex: "0 1 115%" }}>
            Running...
          </Button>
        ) : (
          <>
            <Button
              onClick={(e: React.SyntheticEvent) => {
                e.preventDefault();
                executeWorkflow(closeModal, false);
              }}
            >
              Run
            </Button>
            <Button
              onClick={(e: React.SyntheticEvent) => {
                e.preventDefault();
                executeWorkflow(closeModal, true);
              }}
            >
              Run and View
            </Button>
          </>
        )}
      </ModalFooter>
    </ModalForm>
  );
};

export default WorkflowRunModalContent;
