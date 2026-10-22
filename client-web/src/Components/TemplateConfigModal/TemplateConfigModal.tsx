import React from "react";
import { Button } from "@carbon/react";
import { Add, Edit } from "@carbon/react/icons";
import { ComposedModal, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { DataDrivenInput, ModalTriggerProps } from "Types";
import TemplateConfigModalContent from "./TemplateConfigModalContent";

interface TemplateConfigModalProps {
  field: DataDrivenInput;
  fieldKeys: string[];
  isActive: boolean;
  isEdit: boolean;
  isOldVersion: boolean;
  setFieldValue: (key: string, value: any) => void;
  templateFields: DataDrivenInput[];
  canEdit: boolean;
}

const TemplateConfigModal: React.FC<TemplateConfigModalProps> = (props) => {
  const { isEdit, isOldVersion, isActive, canEdit } = props;
  const editTrigger = ({ openModal }: ModalTriggerProps) => {
    return isEdit ? (
      <TooltipHover direction="bottom" tooltipText={"Edit field"}>
        <Button
          disabled={isOldVersion || !isActive || !canEdit}
          iconDescription="Edit field"
          kind="ghost"
          onClick={openModal}
          renderIcon={Edit}
          size="md"
        />
      </TooltipHover>
    ) : (
      <TooltipHover direction="top" tooltipText={"Add a new field for this task"}>
        <Button
          disabled={isOldVersion || !isActive || !canEdit}
          iconDescription="Add field"
          kind="ghost"
          onClick={openModal}
          renderIcon={Add}
          size="md"
          style={{ width: "8rem" }}
        >
          Add a field
        </Button>
      </TooltipHover>
    );
  };
  return (
    <ComposedModal
      confirmModalProps={{
        title: "Are you sure?",
        children: "Your field will not be saved",
      }}
      modalHeaderProps={{
        title: isEdit ? "Edit field" : "Create field",
      }}
      modalTrigger={editTrigger}
    >
      {({ closeModal, forceCloseModal }) => (
        <TemplateConfigModalContent
          field={props.field}
          fieldKeys={props.fieldKeys}
          isEdit={props.isEdit}
          templateFields={props.templateFields}
          closeModal={closeModal}
          forceCloseModal={forceCloseModal}
          setFieldValue={props.setFieldValue}
        />
      )}
    </ComposedModal>
  );
};

export default TemplateConfigModal;
