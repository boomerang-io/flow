import React from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { DynamicFormik, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import * as Yup from "yup";
import { normaliseInputs } from "Utils/paramsHelper";
import type { DataDrivenInput, Task, WorkflowNodeData } from "Types";
import TaskFormTabs from "../../../shared/TaskFormTabs";
import { foreachInitialValues, foreachValidationShape, taskNameSchema } from "../../../shared/foreach";
import {
  AutoSuggestInput,
  TextAreaSuggestInput,
  TextEditorInput,
  TaskNameTextInput,
  textAreaProps,
  textEditorProps,
  textInputProps,
  toggleProps,
} from "../../../shared/inputs";
import styles from "./TaskForm.module.scss";

interface WorkflowTaskFormProps {
  additionalFormInputs?: Array<Partial<DataDrivenInput>>;
  availableParameters: Array<string>;
  closeModal: () => void;
  node: WorkflowNodeData;
  /** The type of the node being edited, which decides whether it can run for each item. */
  nodeType?: string;
  onSave: (inputs: Record<string, string>, results?: Array<{ name: string; description: string }>) => void;
  otherTaskNames: Array<string>;
  textEditorProps?: any;
  task: Task;
}

function WorkflowTaskForm(props: WorkflowTaskFormProps) {
  const { availableParameters, additionalFormInputs = [], node, task, otherTaskNames } = props;

  // The template's results are listed read-only on their own tab and are not a param; the node
  // keeps its own.
  const handleOnSave = (values: Record<string, string>) => {
    props.node.name = values.taskName;
    props.onSave(values);
    props.closeModal();
  };

  // Add the name input
  let inputs: Array<any> = [
    {
      key: "taskName",
      name: "taskName",
      label: "Task name",
      placeholder: "Enter a task name",
      type: "custom",
      required: true,
      customComponent: TaskNameTextInput,
    },
    ...(task.spec.params ?? []),
    ...additionalFormInputs,
  ];

  // Normalise inputs to match Carbon's requirements
  inputs = normaliseInputs(inputs);

  const initialValues: Record<string, any> = {
    taskName: node.name,
    ...node.params.reduce(
      (accum, curr) => {
        accum[curr.name] = curr.value;
        return accum;
      },
      {} as Record<string, string>,
    ),
    ...foreachInitialValues(node.foreach),
  };

  return (
    <DynamicFormik
      allowCustomPropertySyntax
      validateOnMount
      validationSchemaExtension={Yup.object().shape({
        taskName: taskNameSchema(otherTaskNames),
        ...foreachValidationShape,
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
          <ModalBody aria-label="inputs">
            <TaskFormTabs
              inputs={inputs}
              formikProps={formikProps}
              taskType={props.nodeType}
              results={task.spec.results}
            />
          </ModalBody>
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

export default WorkflowTaskForm;
