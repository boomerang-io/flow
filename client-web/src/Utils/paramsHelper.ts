import { SliderInput } from "Components/Slider";
import { InputType } from "Constants";

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
