import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Add } from "@carbon/react/icons";
import React from "react";
import styles from "./PropertiesModal.module.scss";
import PropertiesModalContent from "./PropertiesModalContent";
import WorkflowEditButton from "./WorkflowEditButton";
import { DataDrivenInput, ModalTriggerProps, WorkflowPropertyActionType } from "Types";

interface PropertiesModalProps {
  isEdit: boolean;
  property?: DataDrivenInput;
  propertyKeys: Array<string>;
  updateWorkflowProperties: (args: { param: DataDrivenInput; type: WorkflowPropertyActionType }) => void;
}

function PropertiesModal(props: PropertiesModalProps) {
  return (
    <ComposedModal
      modalHeaderProps={{
        title: props.isEdit ? "Update Parameter" : "Create Parameter",
        subtitle: props.isEdit ? "Let's change some stuff" : "Let's create a new one",
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => {
        return props.isEdit ? (
          <WorkflowEditButton
            aria-label="Edit"
            data-testid="edit-parameter-button"
            className={styles.editContainer}
            onClick={openModal}
          />
        ) : (
          <button className={styles.createPropertyCard} onClick={openModal} data-testid="create-parameter-button">
            <div className={styles.createContainer}>
              <Add className={styles.createIcon} aria-label="Add" size={32} />
              <p className={styles.createText}>Create a new parameter</p>
            </div>
          </button>
        );
      }}
    >
      {({ closeModal }) => {
        return <PropertiesModalContent closeModal={closeModal} {...props} />;
      }}
    </ComposedModal>
  );
}

export default PropertiesModal;
