import React from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { DynamicFormik, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import * as Yup from "yup";
import { normaliseInputs } from "Utils/paramsHelper";
import { Task, WorkflowNodeData } from "Types";
import {
  AutoSuggestInput,
  ResultsInput,
  TextAreaSuggestInput,
  TextEditorInput,
  TaskNameTextInput,
  textAreaProps,
  textEditorProps,
  textInputProps,
  toggleProps,
} from "../../../shared/inputs";
import styles from "./ScriptForm.module.scss";

interface ScriptFormProps {
  availableParameters: Array<string>;
  closeModal: () => void;
  node: WorkflowNodeData;
  onSave: (inputs: Record<string, string>, results?: Array<{ name: string; description: string }>) => void;
  otherTaskNames: Array<string>;
  textEditorProps: any;
  task: Task;
}

function ScriptForm(props: ScriptFormProps) {
  const { availableParameters, node, otherTaskNames, task } = props;

  const handleOnSave = (values: Record<string, string> & { results: Array<string> }) => {
    const { results, ...rest } = values;
    const formattedOutputs: Array<{ name: string; description: string }> = [];
    results.forEach((result) => {
      const resultsParts = result.split(":");
      formattedOutputs.push({ name: resultsParts[0], description: resultsParts[1] });
    });

    props.onSave(rest, formattedOutputs);
    props.closeModal();
  };

  const taskVersionConfig = task.spec.params ?? [];

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
    ...taskVersionConfig,
    {
      results: node.results,
      name: "results",
      type: "custom",
      customComponent: ResultsInput,
    },
  ];
  // Normalise inputs to match Carbon's requirements
  inputs = normaliseInputs(inputs);

  const initialValues: Record<string, any> = {
    taskName: node.name,
    results: node.results?.map((result) => `${result?.name}:${result?.description}`) ?? [],
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
      validateOnMount
      validationSchemaExtension={Yup.object().shape({
        taskName: Yup.string()
          .required("Enter a task name")
          .notOneOf(otherTaskNames, "Enter a unique value for task name"),
        results: Yup.array(),
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

export default ScriptForm;
