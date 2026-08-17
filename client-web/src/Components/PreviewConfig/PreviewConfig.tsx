import { ComposedModal, DynamicFormik, ModalForm, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, ModalBody } from "@carbon/react";
import { View } from "@carbon/react/icons";
import React from "react";
import TextEditorModal from "Components/TextEditorModal";
import { TEXT_AREA_TYPES } from "Constants/formInputTypes";
import { DataDrivenInput, ModalTriggerProps } from "Types";

const modalHeadertext =
  "This is a preview of what the user sees when editing this Task. The user can also give this task a custom name for their Workflow, and can adjust its connected tasks. You can type in these fields to test any validation requirements.";

const TextEditorInput: React.FC<any> = (props) => {
  return (
    <div key={props.id} style={{ position: "relative", cursor: "pointer", paddingBottom: "1rem" }}>
      <TextEditorModal {...props} {...props.item} />
    </div>
  );
};

const textAreaProps = ({ input, formikProps }: { input: DataDrivenInput; formikProps: any }) => {
  const { values, setFieldValue } = formikProps;
  const { key, type, ...rest } = input;
  //@ts-ignore
  const itemConfig = TEXT_AREA_TYPES[type];
  return {
    autoSuggestions: [],
    formikSetFieldValue: (value: any) => setFieldValue(key, value),
    initialValue: values[key],
    item: input,
    ...itemConfig,
    ...rest,
  };
};

interface PreviewConfigFormProps {
  closeModal: () => void;
  templateConfig: any;
}

const PreviewConfigForm: React.FC<PreviewConfigFormProps> = ({ templateConfig }) => {
  return (
    <DynamicFormik
      allowCustomPropertySyntax
      validateOnMount
      inputs={templateConfig}
      dataDrivenInputProps={{
        TextEditor: TextEditorInput,
      }}
      textEditorProps={textAreaProps}
      toggleProps={() => ({
        orientation: "vertical",
      })}
    >
      {({ inputs }: any) => (
        <ModalForm noValidate>
          <ModalBody>{inputs}</ModalBody>
        </ModalForm>
      )}
    </DynamicFormik>
  );
};

interface PreivewConfigProps {
  templateConfig: any;
  taskTemplateName: string;
}

const PreviewConfig: React.FC<PreivewConfigProps> = ({ templateConfig, taskTemplateName }) => {
  return (
    <ComposedModal
      composedModalProps={{ shouldCloseOnOverlayClick: true }}
      modalHeaderProps={{
        title: `[Preview] ${taskTemplateName}`,
        subtitle: modalHeadertext,
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <TooltipHover direction="top" tooltipText={"Preview what the user sees when they view this task"}>
          <Button
            iconDescription="Preview task"
            renderIcon={View}
            onClick={openModal}
            size="md"
            kind="ghost"
            style={{ width: "7rem" }}
          >
            Preview
          </Button>
        </TooltipHover>
      )}
    >
      {({ closeModal }) => <PreviewConfigForm templateConfig={templateConfig} closeModal={closeModal} />}
    </ComposedModal>
  );
};
export default PreviewConfig;
