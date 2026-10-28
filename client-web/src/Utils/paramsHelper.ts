import type { DynamicInput, FormInput, InputGovernor } from "@boomerang-io/carbon-addons-boomerang-react";
import { SliderInput } from "Components/Slider";
import { InputType } from "Constants";
import type { DataDrivenInput } from "Types";

/**
 * The input shape DynamicFormik actually reads. The add-ons' `DynamicInput` type requires all nine
 * InputGovernor fields, but DynamicFormik reads them only for an input that names a `governingKey`;
 * and it types `min`/`max` as strings, but DynamicFormik hands them straight to yup's `min`/`max`,
 * so the backend param model's numbers (DataDrivenInput#min) are what it expects.
 */
export type FormikInput = Omit<FormInput, "min" | "max"> &
  Partial<InputGovernor> & {
    min?: FormInput["min"] | DataDrivenInput["min"];
    max?: FormInput["max"] | DataDrivenInput["max"];
  };

/**
 * Hand inputs to DynamicFormik's `inputs` prop. The one type assertion lives here: a FormikInput
 * carries everything DynamicFormik reads (see FormikInput); the add-ons' type is stricter than that.
 * @param inputs
 * @returns
 */
export function toDynamicInputs(inputs: Array<FormikInput>): Array<DynamicInput> {
  return inputs as Array<DynamicInput>;
}

/**
 * Route param types DataDrivenInput has no branch for to a local component via its
 * `customComponent` escape hatch. Today that is only `slider` (Constants#InputType.Slider);
 * without this the input renders as nothing at all.
 * @param inputs
 * @returns
 */
export function attachCustomInputComponents(inputs: Array<any>): Array<any> {
  return inputs.map((input) =>
    input.type === InputType.Slider && !input.customComponent ? { ...input, customComponent: SliderInput } : input,
  );
}

/**
 * Helper function to add required Carbon props of key and defaultValue
 * @param inputs
 * @returns
 */
export function normaliseInputs(inputs: Array<any>): Array<any> {
  return attachCustomInputComponents(inputs).map((input) => ({
    ...input,
    defaultValue: input.default ?? null, // Ensure defaultValue is set
    key: input.key ?? input.name, // Ensure key is set
  }));
}

// Exactly one Flow reference - $(params.x), $(<scope>.params.x) or $(tasks.t.results.r), each with an optional
// path - and not shell text such as $(date); the same rule the server applies to secret values.
const FLOW_REFERENCE =
  /^\$\((?:params\.[\w-]+|(?:workflow|workspace|global|context|team)\.params\.[\w-]+|tasks\.[\w-]+\.results\.[\w-]+)(?:\.[^\s()]+)?\)$/i;

export function isFlowReference(value: unknown): boolean {
  return typeof value === "string" && FLOW_REFERENCE.test(value.trim());
}
