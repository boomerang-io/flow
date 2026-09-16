import React from "react";
import type { FormikProps } from "formik";
import BasicSlider from "./Slider";

/**
 * The `slider` param type's DynamicFormik adapter.
 *
 * DataDrivenInput has no branch for `slider`, so a param of that type is routed here through its
 * `customComponent` escape hatch (Utils/paramsHelper#attachCustomInputComponents). DataDrivenInput
 * hands a custom component `{...allInputProps, ...componentProps, value, ...restInputProps,
 * formikProps}`, which is why `name`/`id` arrive as DynamicFormik's bracketed path (`['temperature']`)
 * and `min`/`max`/`step` pass straight through untouched.
 *
 * The value is written back as a **string**, like every other task param value - the engine reads
 * params as strings and `WorkflowNodeData.params` is `Array<{ name: string; value: string }>`.
 */
interface SliderInputProps {
  // Optional because the task-update modal renders both columns' params through DataDrivenInput
  // directly, without a Formik context - those are read-only, so nothing is ever written back.
  formikProps?: FormikProps<any>;
  id?: string;
  name?: string;
  label?: string;
  labelText?: string;
  helperText?: string;
  disabled?: boolean;
  readOnly?: boolean;
  min?: number | string;
  max?: number | string;
  step?: number | string;
  value?: unknown;
}

function toNumber(value: unknown, fallback: number): number {
  const parsed = typeof value === "number" ? value : parseFloat(String(value ?? ""));
  return Number.isNaN(parsed) ? fallback : parsed;
}

export default function SliderInput(props: SliderInputProps) {
  const { formikProps, helperText, id, label, labelText, name, disabled, readOnly } = props;
  const min = toNumber(props.min, 0);
  const max = toNumber(props.max, 100);
  const step = toNumber(props.step, 1);
  const fieldKey = name ?? id ?? "";

  return (
    <BasicSlider
      data-testid={`slider-${fieldKey}`}
      disabled={disabled || readOnly}
      helperText={helperText}
      id={id ?? fieldKey}
      labelText={labelText ?? label ?? fieldKey}
      max={max}
      min={min}
      step={step}
      sliderValue={toNumber(props.value, min)}
      onChange={(value: number) => {
        formikProps?.setFieldTouched(fieldKey, true, false);
        formikProps?.setFieldValue(fieldKey, String(value));
      }}
    />
  );
}
