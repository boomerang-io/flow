import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import React from "react";
import styles from "./PropertiesModal.module.scss";
import PropertiesModalContent from "./PropertiesModalContent";
import { DataDrivenInput, WorkflowPropertyActionType } from "Types";

interface PropertiesModalProps {
  isEdit: boolean;
  isOpen: boolean;
  onClose: () => void;
  property?: DataDrivenInput;
  propertyKeys: Array<string>;
  updateWorkflowProperties: (args: { param: DataDrivenInput; type: WorkflowPropertyActionType }) => void;
  workflowName: string;
}

function PropertiesModal(props: PropertiesModalProps) {
  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      isOpen={props.isOpen}
      modalHeaderProps={{
        label: props.workflowName,
        title: props.isEdit ? "Edit parameter" : "Add parameter",
      }}
      onCloseModal={props.onClose}
    >
      {({ closeModal }) => {
        return <PropertiesModalContent closeModal={closeModal} {...props} />;
      }}
    </ComposedModal>
  );
}

export default PropertiesModal;
