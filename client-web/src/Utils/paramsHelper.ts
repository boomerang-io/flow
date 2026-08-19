/**
 * Helper function to add required Carbon props of key and defaultValue
 * @param inputs
 * @returns
 */
export function normaliseInputs(inputs: Array<any>): Array<any> {
  return inputs.map((input) => ({
    ...input,
    defaultValue: input.default ?? null, // Ensure defaultValue is set
    key: input.key ?? input.name, // Ensure key is set
  }));
}
