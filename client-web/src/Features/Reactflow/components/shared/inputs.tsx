import React from "react";
import { AutoSuggest, TextInput, TextArea, Creatable } from "@boomerang-io/carbon-addons-boomerang-react";
import { json } from "d3";
import { FormikProps } from "formik";
import TextEditorModal from "Components/TextEditorModal";
import { INPUT_TYPES, TEXT_AREA_TYPES, SUPPORTED_AUTOSUGGEST_TYPES } from "Constants/formInputTypes";
import { DataDrivenInput } from "Types";
import styles from "./inputs.module.scss";

// `input.type` is a free-form string on DataDrivenInput; look configs up safely
// rather than indexing the frozen config maps with an unchecked key.
function getTypeConfig<T extends Record<string, unknown>>(configMap: T, type: string): T[keyof T] | undefined {
  return Object.prototype.hasOwnProperty.call(configMap, type) ? configMap[type as keyof T] : undefined;
}

export const AutoSuggestInput = (props: any) => {
  if (!SUPPORTED_AUTOSUGGEST_TYPES.includes(props.type)) {
    return <TextInput {...props} onChange={(e) => props.onChange(e.target.value)} />;
  }
  return (
    <div key={props.id}>
      <AutoSuggest
        {...props}
        initialValue={props?.initialValue !== "" ? props?.initialValue : props?.inputProps?.defaultValue}
      >
        <TextInput tooltipContent={props.tooltipContent} disabled={props?.inputProps?.readOnly} />
      </AutoSuggest>
    </div>
  );
};

export const TextAreaSuggestInput = (props: any) => {
  return (
    <div key={props.id}>
      <AutoSuggest
        {...props}
        initialValue={props?.initialValue !== "" ? props?.initialValue : props?.inputProps?.defaultValue}
      >
        <TextArea
          disabled={props?.inputProps?.readOnly}
          tooltipContent={props.tooltipContent}
          labelText={props.label}
        />
      </AutoSuggest>
    </div>
  );
};

export const TextEditorInput = (props: any) => {
  return <TextEditorModal {...props} {...props.inputProps} />;
};

export const TaskNameTextInput = ({ formikProps, ...input }: DataDrivenInput & { formikProps: FormikProps<any> }) => {
  const { errors, touched } = formikProps;
  const hasError = Boolean(errors[input.id]);
  const isTouched = Boolean(touched[input.id]);
  // The task name is always a plain string; DataDrivenInput#value/#defaultValue are
  // typed broadly (they also cover list/key-value inputs), so narrow them here
  // rather than at the vendor TextInput.
  const defaultValue = typeof input.defaultValue === "string" ? input.defaultValue : undefined;
  const value = typeof input.value === "string" ? input.value : undefined;
  return (
    <>
      <TextInput {...input} defaultValue={defaultValue} value={value} invalid={hasError} invalidText={isTouched} onChange={formikProps.handleChange} />
      <hr className={styles.divider} />
      <h2 className={styles.inputsTitle}>Specifics</h2>
    </>
  );
};

export const ResultsInput = ({ formikProps, ...input }: DataDrivenInput & { formikProps: FormikProps<any> }) => {
  // Results are stored as "name:description" strings (see CustomTaskForm's
  // initialValues/handleOnSave); DataDrivenInput#value is typed broadly to also
  // cover object/key-value inputs, so narrow it to what Creatable actually accepts.
  const rawValue = input.value;
  const value: string | string[] | undefined =
    typeof rawValue === "string" ? rawValue : Array.isArray(rawValue) && rawValue.every((item) => typeof item === "string") ? rawValue : undefined;
  return (
    <>
      <hr className={styles.divider} />
      <h2 className={styles.inputsTitle}>Result Parameters</h2>
      <Creatable
        {...input}
        value={value}
        createKeyValuePair
        keyLabelText="Name"
        valueLabelText="Description"
        onChange={(value) => formikProps.setFieldValue("results", value)}
      />
    </>
  );
};

export function formatAutoSuggestParameters(availableParameters: Array<string>) {
  return availableParameters.map((parameter) => ({
    value: `$(${parameter})`,
    label: parameter,
  }));
}

export const textAreaProps =
  (availableParameters: Array<string>) =>
  ({ input, formikProps }: { formikProps: FormikProps<any>; input: DataDrivenInput }) => {
    const { errors, handleBlur, touched, values, setFieldValue } = formikProps;
    const { name, type, ...rest } = input;
    const itemConfig = getTypeConfig(TEXT_AREA_TYPES, type);
    const safeKey = `['${name}']`;
    return {
      autoSuggestions: formatAutoSuggestParameters(availableParameters),
      onChange: (value: React.FormEvent<HTMLInputElement>) => setFieldValue(safeKey, value),
      initialValue: values[name] || values[safeKey],
      inputProps: {
        onBlur: handleBlur,
        invalid: touched[name] && Boolean(errors[name]),
        invalidText: errors[name],
        ...itemConfig,
        ...rest,
        name: safeKey,
        id: safeKey,
      },
    };
  };

export const textEditorProps =
  (availableParameters: Array<string>, textEditorProps: any) =>
  ({ input, formikProps }: { formikProps: FormikProps<any>; input: DataDrivenInput }) => {
    const { values, setFieldValue } = formikProps;
    const { name, type, ...rest } = input;
    const itemConfig = getTypeConfig(TEXT_AREA_TYPES, type);
    const safeKey = `['${name}']`;

    return {
      autoSuggestions: formatAutoSuggestParameters(availableParameters),
      formikSetFieldValue: (value: React.FormEvent<HTMLInputElement>) => setFieldValue(safeKey, value),
      initialValue: values[name] || values[safeKey],
      ...rest,
      ...itemConfig,
      ...textEditorProps,
      type,
      name: safeKey,
      id: safeKey,
    };
  };

export const textInputProps =
  (availableParameters: Array<string>) =>
  ({ formikProps, input }: { formikProps: FormikProps<any>; input: DataDrivenInput }) => {
    const { errors, handleBlur, touched, setFieldValue, values } = formikProps;
    const { name, type, ...rest } = input;
    const itemConfig = getTypeConfig(INPUT_TYPES, type);
    const safeKey = `['${name}']`;
    return {
      autoSuggestions: formatAutoSuggestParameters(availableParameters),
      onChange: (value: React.FormEvent<HTMLInputElement>) => setFieldValue(safeKey, value),
      initialValue: values[name] || values[safeKey],
      inputProps: {
        onBlur: handleBlur,
        invalid: touched[name] && Boolean(errors[name]),
        invalidText: errors[name],
        ...itemConfig,
        ...rest,
        name: safeKey,
        id: safeKey,
      },
    };
  };

export const toggleProps = () => {
  return {
    orientation: "vertical",
  };
};
