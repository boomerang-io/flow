import { ComposedModal, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button } from "@carbon/react";
import { Add, Edit } from "@carbon/react/icons";
import React from "react";
import TemplateConfigModalContent from "./TemplateConfigModalContent";
import { ResultParameter, ModalTriggerProps } from "Types";

interface TemplateParametersModalProps {
  result: ResultParameter;
  resultKeys: string[];
  isActive: boolean;
  isEdit: boolean;
  isOldVersion: boolean;
  setFieldValue: (key: string, value: any) => void;
  templateFields: ResultParameter[];
  canEdit: boolean;
  index: number;
}

const TemplateParametersModal: React.FC<TemplateParametersModalProps> = (props) => {
  const { isEdit, isOldVersion, isActive, canEdit } = props;
  const editTrigger = ({ openModal }: ModalTriggerProps) => {
    return isEdit ? (
      <TooltipHover direction="bottom" tooltipText={"Edit result parameter"}>
        <Button
          disabled={isOldVersion || !isActive || !canEdit}
          iconDescription="Edit result parameter"
          kind="ghost"
          onClick={openModal}
          renderIcon={Edit}
          size="md"
        />
      </TooltipHover>
    ) : (
      <TooltipHover direction="top" tooltipText={"Add a new result parameter for this task"}>
        <Button
          disabled={isOldVersion || !isActive || !canEdit}
          iconDescription="Add result parameter"
          kind="ghost"
          onClick={openModal}
          renderIcon={Add}
          size="md"
          style={{ width: "13.5rem" }}
        >
          Add a Result Parameter
        </Button>
      </TooltipHover>
    );
  };
  return (
    <ComposedModal
      confirmModalProps={{
        title: "Are you sure?",
        children: "Your result parameter will not be saved",
      }}
      modalHeaderProps={{
        title: isEdit ? "Edit result parameter" : "Create field",
      }}
      modalTrigger={editTrigger}
    >
      {({ closeModal, forceCloseModal }) => (
        <TemplateConfigModalContent
          result={props.result}
          resultKeys={props.resultKeys}
          isEdit={props.isEdit}
          templateFields={props.templateFields}
          closeModal={closeModal}
          forceCloseModal={forceCloseModal}
          setFieldValue={props.setFieldValue}
          index={props.index}
        />
      )}
    </ComposedModal>
  );
};

export default TemplateParametersModal;
