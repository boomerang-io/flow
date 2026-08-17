import React, { useState } from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { ComboBox, DynamicFormik, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { FormikProps } from "formik";
import * as Yup from "yup";
import { useEditorContext } from "Hooks";
import { normaliseInputs } from "Utils/paramsHelper";
import { DataDrivenInput, Task, WorkflowNodeData } from "Types";
import {
  AutoSuggestInput,
  TextAreaSuggestInput,
  TextEditorInput,
  TaskNameTextInput,
  textAreaProps,
  textEditorProps,
  textInputProps,
  toggleProps,
} from "../../shared/inputs";
import styles from "./RunWorkflowForm.module.scss";

interface RunWorkflowFormProps {
  closeModal: () => void;
  availableParameters: Array<string>;
  node: WorkflowNodeData;
  onSave: (inputs: Record<string, string>, results?: Array<{ name: string; description: string }>) => void;
  otherTaskNames: Array<string>;
  textEditorProps: any;
  task: Task;
}

function RunWorkflowForm(props: RunWorkflowFormProps) {
  const { workflowsQueryData } = useEditorContext();
  const { availableParameters, node, otherTaskNames } = props;
  const paramWorkflowId = node?.params.find((param) => param.name === "workflowRef")?.value;
  const [selectedWorkflowRef, setSelectedWorkflowRef] = useState(paramWorkflowId ?? "");

  const workflows = workflowsQueryData.content;
  const workflowsMapped = workflows?.map((workflow) => ({ label: workflow.displayName, value: workflow.name })) ?? [];
  const selectedWorkflowConfg = workflows.find((workflow) => workflow.name === selectedWorkflowRef)?.params ?? [];

  const activeInputs: Record<string, string> = {};
  if (selectedWorkflowConfg) {
    selectedWorkflowConfg.forEach((item) => {
      const name = item.name;
      if (name) {
        //@ts-ignore
        activeInputs[name] = item.default;
      }
    });
  }

  const handleOnSave = (values: any) => {
    props.node.name = values.taskName;
    props.onSave(values);
    props.closeModal();
  };

  const WorkflowSelectionInput = ({
    formikProps,
    ...input
  }: DataDrivenInput & {
    formikProps: FormikProps<any>;
  }) => {
    const { errors, touched, setFieldValue, values } = formikProps;
    const error = errors[input.id];
    const touch = touched[input.id];
    const initialSelectedItem = values.workflowRef
      ? workflowsMapped.find((workflow) => workflow.value === values.workflowRef)
      : "";
    return (
      <ComboBox
        helperText={input.helperText}
        id="workflow-select"
        onChange={({ selectedItem }) => {
          const value = selectedItem?.value ?? "";
          setFieldValue("workflowRef", value);
          setSelectedWorkflowRef(value);
        }}
        items={workflowsMapped}
        initialSelectedItem={initialSelectedItem}
        titleText="Workflow"
        placeholder="Select a Workflow"
        invalid={Boolean(error) && Boolean(touch)}
        invalidText={error}
      />
    );
  };

  // Add the name and future inputs
  let inputs: Array<any> = [
    {
      name: "taskName",
      id: "taskName",
      label: "Task Name",
      placeholder: "Enter a task name",
      type: "custom",
      required: true,
      customComponent: TaskNameTextInput,
    },
    {
      name: "workflowRef",
      id: "workflowRef",
      type: "custom",
      required: true,
      helperText: "The Workflow you wish to run",
      customComponent: WorkflowSelectionInput,
    },
    ...selectedWorkflowConfg,
  ];

  // Normalise inputs to match Carbon's requirements
  inputs = normaliseInputs(inputs);

  const initialValues: Record<string, any> = {
    taskName: node.name,
    workflowRef: selectedWorkflowRef,
    ...activeInputs,
    ...node.params.reduce(
      (accum, curr) => {
        accum[curr.name] = curr.value;
        return accum;
      },
      {} as Record<string, string>,
    ),
  };

  return (
    <DynamicFormik
      allowCustomPropertySyntax
      enableReinitialize
      validateOnMount
      validationSchemaExtension={Yup.object().shape({
        taskName: Yup.string()
          .required("Enter a task name")
          .notOneOf(otherTaskNames, "Enter a unique value for task name"),
        workflowRef: Yup.string().required("Select a workflow"),
      })}
      initialValues={initialValues}
      inputs={inputs}
      onSubmit={handleOnSave}
      dataDrivenInputProps={{
        TextInput: AutoSuggestInput,
        TextEditor: TextEditorInput,
        TextArea: TextAreaSuggestInput,
      }}
      textAreaProps={textAreaProps(availableParameters)}
      textEditorProps={textEditorProps(availableParameters, props.textEditorProps)}
      textInputProps={textInputProps(availableParameters)}
      toggleProps={toggleProps}
    >
      {({ inputs, formikProps }) => (
        <ModalForm noValidate className={styles.container} onSubmit={formikProps.handleSubmit}>
          <ModalBody aria-label="inputs">{inputs}</ModalBody>
          <ModalFooter>
            <Button kind="secondary" onClick={props.closeModal}>
              Cancel
            </Button>
            <Button type="submit" disabled={!formikProps.isValid}>
              Apply
            </Button>
          </ModalFooter>
        </ModalForm>
      )}
    </DynamicFormik>
  );
}

export default RunWorkflowForm;
