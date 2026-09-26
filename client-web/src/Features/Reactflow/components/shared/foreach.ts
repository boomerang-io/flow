import * as Yup from "yup";
import { NodeType } from "Constants";
import type { WorkflowTaskForeach } from "Types";

/**
 * The "Run for each item" task setting. The form carries it as two flat Formik values beside
 * the task's params; the node carries it as `foreach: { items }`, where items is either one
 * reference string or a JSON array literal.
 */
export const FOREACH_MAX_ITEMS = 256;
export const FOREACH_ENABLED_KEY = "foreachEnabled";
export const FOREACH_ITEMS_KEY = "foreachItems";
export const FOREACH_KEYS = [FOREACH_ENABLED_KEY, FOREACH_ITEMS_KEY];

/** The `type` the palette's synthetic "For each" entry carries on drag; never a node type. */
export const FOREACH_PALETTE_TYPE = "foreach";

/**
 * The task types the "Repeat a task for each item" picker offers: the ones the dispatcher runs.
 * Control tasks (decision, approval, wait, locks, run workflow, ...) make no sense repeated.
 */
export const FOREACH_TASK_TYPES: ReadonlyArray<string> = [
  NodeType.Template,
  NodeType.Script,
  NodeType.CustomTask,
  NodeType.Ai,
];

const REFERENCE_PATTERN = /^\$\([^()\s]+\)$/;

export type ForeachFormValues = {
  [FOREACH_ENABLED_KEY]: boolean;
  [FOREACH_ITEMS_KEY]: string;
};

/** The error for an Items value, or undefined when it is one reference or a JSON array. */
export function foreachItemsError(value?: string): string | undefined {
  const trimmed = (value ?? "").trim();
  if (!trimmed) {
    return "Enter the items";
  }
  if (REFERENCE_PATTERN.test(trimmed)) {
    return undefined;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return "Enter a JSON array or a single reference such as $(params.items)";
  }
  if (!Array.isArray(parsed)) {
    return "Enter a JSON array or a single reference such as $(params.items)";
  }
  if (parsed.length > FOREACH_MAX_ITEMS) {
    return `Enter at most ${FOREACH_MAX_ITEMS} items`;
  }
  return undefined;
}

/** A valid Items value as the node stores it: the reference string, or the parsed array. */
export function parseForeachItems(value: string): WorkflowTaskForeach["items"] {
  const trimmed = value.trim();
  return REFERENCE_PATTERN.test(trimmed) ? trimmed : (JSON.parse(trimmed) as Array<unknown>);
}

/** How a stored Items value reads in the form. */
export function formatForeachItems(items?: WorkflowTaskForeach["items"]): string {
  if (items === undefined || items === null) {
    return "";
  }
  return typeof items === "string" ? items : JSON.stringify(items);
}

export function foreachInitialValues(foreach?: WorkflowTaskForeach): ForeachFormValues {
  return {
    [FOREACH_ENABLED_KEY]: Boolean(foreach),
    [FOREACH_ITEMS_KEY]: formatForeachItems(foreach?.items),
  };
}

/** Spread into each task form's `validationSchemaExtension` shape. */
export const foreachValidationShape = {
  [FOREACH_ENABLED_KEY]: Yup.boolean(),
  [FOREACH_ITEMS_KEY]: Yup.string().test("foreachItems", function (value) {
    if (!this.parent[FOREACH_ENABLED_KEY]) {
      return true;
    }
    const message = foreachItemsError(value);
    return message ? this.createError({ message }) : true;
  }),
};

/**
 * Pull the for-each values off a submitted form record, returning the node's `foreach` (or
 * undefined when the setting is off) and the remaining values, which are the task's params.
 */
export function splitForeachValues<T extends Record<string, any>>(
  values: T,
): { foreach: WorkflowTaskForeach | undefined; rest: Omit<T, typeof FOREACH_ENABLED_KEY | typeof FOREACH_ITEMS_KEY> } {
  const { [FOREACH_ENABLED_KEY]: enabled, [FOREACH_ITEMS_KEY]: items, ...rest } = values;
  const foreach = enabled && typeof items === "string" ? { items: parseForeachItems(items) } : undefined;
  return { foreach, rest };
}
