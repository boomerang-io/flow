import React from "react";
import { ModalForm, RadioGroup } from "@boomerang-io/carbon-addons-boomerang-react";
import { WorkflowView } from "Constants";
import { FlowTeam, CreateWorkflowSummary, Workflow } from "Types";
import ImportWorkflowContent from "../ImportWorkflowContent";
import styles from "./importWorkflowContainer.module.scss";

interface ImportWorkflowContainerProps {
  closeModal: () => void;
  importError: any;
  importWorkflow: (workflowData: Workflow, closeModal: () => void, team: FlowTeam) => Promise<void>;
  workflows: Array<Workflow>;
}

const CreateWorkflowContainer: React.FC<ImportWorkflowContainerProps> = ({
  closeModal,
  importError,
  importWorkflow,
  workflows,
}) => {
  const existingWorkflowNames = workflows?.map((workflow) => workflow.name) ?? [];

  return (
    <ModalForm>
      <ImportWorkflowContent
        closeModal={closeModal}
        existingWorkflowNames={existingWorkflowNames}
        importError={importError}
        importWorkflow={importWorkflow}
      />
    </ModalForm>
  );
};

export default CreateWorkflowContainer;
