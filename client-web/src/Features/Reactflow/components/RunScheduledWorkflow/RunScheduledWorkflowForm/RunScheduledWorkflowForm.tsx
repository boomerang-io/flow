import React, { useState } from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { ComboBox, DynamicFormik, ModalForm, TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import type { FormikProps } from "formik";
import * as Yup from "yup";
import { useEditorContext } from "Hooks";
import { timezoneOptions, defaultTimeZone, transformTimeZone } from "Utils/dateHelper";
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
import styles from "./RunScheduledWorkflowForm.module.scss";

interface RunScheduledWorkflowFormProps {
  closeModal: () => void;
  availableParameters: Array<string>;
  node: WorkflowNodeData;
  otherTaskNames: Array<string>;
  onSave: (inputs: Record<string, string>, results?: Array<{ name: string; description: string }>) => void;
  textEditorProps: Record<string, any>;
  task: Task;
}

const TimeInput = ({
  formikProps,
  ...input
}: DataDrivenInput & {
  formikProps: FormikProps<any>;
}) => {
  const { errors, touched, handleChange, values } = formikProps;
  const error = errors[input.id];
  const hasBeenTouched = Boolean(touched[input.id]);
  if (values.futurePeriod === "minutes" || values.futurePeriod === "hours") {
    return null;
  }

  return (
    <TextInput
      helperText={input.helperText}
      id="time"
      invalid={Boolean(error) && hasBeenTouched}
      invalidText={error}
      labelText="Time"
      name="time"
      onChange={handleChange}
      placeholder="e.g. 8:00"
      type="time"
      value={values["time"]}
    />
  );
};

const TimeZoneInput = ({
  formikProps,
  ...input
}: DataDrivenInput & {
  formikProps: FormikProps<any>;
}) => {
  const { errors, touched, values } = formikProps;
  const error = errors[input.id];
  const hasBeenTouched = Boolean(touched[input.id]);
  if (values.futurePeriod === "minutes" || values.futurePeriod === "hours") {
    return null;
  }

  return (
    <ComboBox
      helperText={input.helperText}
      id="timezone"
      initialSelectedItem={values.timezone}
      //@ts-ignore
      items={timezoneOptions}
      invalid={Boolean(error) && hasBeenTouched}
      invalidText={error}
      onChange={({ selectedItem }) => {
        formikProps.setFieldValue("timezone", selectedItem);
      }}
      placeholder="e.g. US/Central (UTC -06:00)"
      titleText="Time Zone"
    />
  );
};

function RunScheduledWorkflowForm(props: RunScheduledWorkflowFormProps) {
  const { workflowsQueryData } = useEditorContext();
  const { availableParameters, node, task, otherTaskNames } = props;
  const paramWorkflowRef = node?.params.find((param) => param.name === "workflowRef")?.value;
  const [selectedWorkflowRef, setSelectedWorkflowRef] = useState(paramWorkflowRef ?? "");

  const workflows = workflowsQueryData.content;
  const workflowsMapped = workflows?.map((workflow) => ({ label: workflow.displayName, value: workflow.name })) ?? [];
  const selectedWorkflowParams = workflows.find((workflow) => workflow.name === selectedWorkflowRef)?.params ?? [];

  const handleOnSave = (values: { taskName: string; timezone: { value: string } }) => {
    props.node.name = values.taskName;
    const valuesToSave = { ...values, timezone: values.timezone.value };
    props.onSave(valuesToSave);
    props.closeModal();
  };
  const activeInputs: Record<string, string> = {};
  if (selectedWorkflowParams) {
    selectedWorkflowParams.forEach((item) => {
      const key = item.name;
      if (key) {
        //@ts-ignore
        activeInputs[key] = item.default;
      }
    });
  }

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
      name: "futureIn",
      id: "futureIn",
      label: "Interval",
      helperText: "Length of the selected interval period",
      placeholder: "e.g. 10",
      type: "number",
      required: true,
      min: 1,
    },
    {
      name: "futurePeriod",
      id: "futurePeriod",
      label: "Interval Period",
      placeholder: "e.g. Days",
      type: "select",
      options: [
        { key: "minutes", value: "Minutes" },
        { key: "hours", value: "Hours" },
        { key: "days", value: "Days" },
        { key: "weeks", value: "Weeks" },
        { key: "months", value: "Months" },
      ],
      required: true,
    },
    {
      name: "time",
      id: "time",
      label: "At Time",
      helperText: "When in the future you want the Workflow to execute",
      type: "custom",
      required: true,
      customComponent: TimeInput,
    },
    {
      name: "timezone",
      id: "timezone",
      required: true,
      type: "custom",
      helperText: "What time zone to execute in",
      customComponent: TimeZoneInput,
    },
    {
      name: "workflowRef",
      id: "workflowRef",
      type: "custom",
      required: true,
      helperText: "When in the future you want the Workflow to execute",
      customComponent: WorkflowSelectionInput,
    },
    ...selectedWorkflowParams,
  ];

  // Normalise inputs to match Carbon's requirements
  inputs = normaliseInputs(inputs);

  const initTime = node?.params.find((param) => param.name === "time")?.value ?? "";
  const initTimeZone = transformTimeZone(
    node?.params.find((param) => param.name === "timezone")?.value ?? defaultTimeZone,
  );

  const initialValues: Record<string, any> = {
    taskName: node.name,
    workflowRef: selectedWorkflowRef,
    time: initTime,
    timezone: initTimeZone,
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
      validationSchemaExtension={Yup.object().shape({
        futureIn: Yup.number().required("Interval is required ").min(1, "Must be at least one interval in future"),
        futurePeriod: Yup.string().required("Interval period is required"),
        taskName: Yup.string()
          .required("Enter a task name")
          .notOneOf(otherTaskNames, "Enter a unique value for task name"),
        time: Yup.string().test("timeRequired", "Time is required", (value, ctx) => {
          const futurePeriod = ctx.parent.futurePeriod;
          if (!value && (futurePeriod === "days" || futurePeriod === "weeks" || futurePeriod === "months")) {
            return false;
          }
          return true;
        }),
        timezone: Yup.object().test("timeZoneRequired", "Time Zone is required", (value, ctx) => {
          const futurePeriod = ctx.parent.futurePeriod;
          if (!value.value && (futurePeriod === "days" || futurePeriod === "weeks" || futurePeriod === "months")) {
            return false;
          }
          return true;
        }),
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

export default RunScheduledWorkflowForm;
