import * as Yup from "yup";
import type { WorkflowTaskRetry } from "Types";

/**
 * The "Retry" task setting. The form carries it as four flat Formik values beside the task's
 * params; the node carries it as `retry: { max, backoff }`. Times are seconds. Fields left blank
 * are absent on the node, so the engine's defaults apply (10 s doubling to 300 s), and zero
 * retries is no `retry` at all.
 */
export const RETRY_MAX_KEY = "retryMax";
export const RETRY_BACKOFF_TYPE_KEY = "retryBackoffType";
export const RETRY_BACKOFF_INITIAL_KEY = "retryBackoffInitial";
export const RETRY_BACKOFF_MAX_KEY = "retryBackoffMax";
export const RETRY_KEYS = [RETRY_MAX_KEY, RETRY_BACKOFF_TYPE_KEY, RETRY_BACKOFF_INITIAL_KEY, RETRY_BACKOFF_MAX_KEY];

export const RETRY_MAX_LIMIT = 10;
export const RETRY_MAX_HELPER_TEXT = "Attempts beyond the first when the task fails. 0 to 10.";

export type RetryBackoffType = "fixed" | "exponential";

export const RETRY_BACKOFF_OPTIONS: ReadonlyArray<{ value: RetryBackoffType; label: string }> = [
  { value: "exponential", label: "Exponential" },
  { value: "fixed", label: "Fixed" },
];

export type RetryFormValues = {
  [RETRY_MAX_KEY]: string;
  [RETRY_BACKOFF_TYPE_KEY]: RetryBackoffType;
  [RETRY_BACKOFF_INITIAL_KEY]: string;
  [RETRY_BACKOFF_MAX_KEY]: string;
};

const toText = (value?: number) => (value === undefined || value === null ? "" : String(value));

export function retryInitialValues(retry?: WorkflowTaskRetry): RetryFormValues {
  return {
    [RETRY_MAX_KEY]: toText(retry?.max),
    [RETRY_BACKOFF_TYPE_KEY]: retry?.backoff?.type ?? "exponential",
    [RETRY_BACKOFF_INITIAL_KEY]: toText(retry?.backoff?.initial),
    [RETRY_BACKOFF_MAX_KEY]: toText(retry?.backoff?.max),
  };
}

const wholeNumber = (label: string, min: number, max?: number) => {
  let schema = Yup.number()
    .transform((value, original) => (original === "" || original === null ? undefined : value))
    .typeError(`${label} must be a number`)
    .integer(`${label} must be a whole number`)
    .min(min, `${label} must be at least ${min}`);
  if (max !== undefined) {
    schema = schema.max(max, `${label} must be at most ${max}`);
  }
  return schema;
};

/** Spread into each task form's `validationSchemaExtension` shape. */
export const retryValidationShape = {
  [RETRY_MAX_KEY]: wholeNumber("Retries", 0, RETRY_MAX_LIMIT),
  [RETRY_BACKOFF_TYPE_KEY]: Yup.string(),
  [RETRY_BACKOFF_INITIAL_KEY]: wholeNumber("Initial wait", 1),
  [RETRY_BACKOFF_MAX_KEY]: wholeNumber("Maximum wait", 1),
};

function toNumber(value: unknown): number | undefined {
  if (value === "" || value === undefined || value === null) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Pull the retry values off a submitted form record, returning the node's `retry` (undefined when
 * the retries are blank or 0) and the remaining values, which are the task's params.
 */
export function splitRetryValues<T extends Record<string, any>>(
  values: T,
): { retry: WorkflowTaskRetry | undefined; rest: Omit<T, keyof RetryFormValues> } {
  const {
    [RETRY_MAX_KEY]: maxValue,
    [RETRY_BACKOFF_TYPE_KEY]: type,
    [RETRY_BACKOFF_INITIAL_KEY]: initialValue,
    [RETRY_BACKOFF_MAX_KEY]: maxWaitValue,
    ...rest
  } = values;
  const max = toNumber(maxValue);
  if (!max) {
    return { retry: undefined, rest };
  }
  const initial = toNumber(initialValue);
  // The maximum wait only bounds exponential growth.
  const maxWait = type === "fixed" ? undefined : toNumber(maxWaitValue);
  const backoff: NonNullable<WorkflowTaskRetry["backoff"]> = {};
  if (type === "fixed") {
    backoff.type = "fixed";
  }
  if (initial !== undefined) {
    backoff.initial = initial;
  }
  if (maxWait !== undefined) {
    backoff.max = maxWait;
  }
  return { retry: Object.keys(backoff).length > 0 ? { max, backoff } : { max }, rest };
}
