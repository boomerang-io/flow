import { Task } from "Types";

export function emailIsValid(email: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

//helper function to replace empty strings with null values
export const swapValue = (obj: Record<string, any>) => {
  Object.keys(obj).forEach((key) => {
    if (obj[key] === "") {
      obj[key] = null;
    }
  });
};

/**
 * Convert the `Creatable createKeyValuePair` form value - an array of "key:value" strings, the
 * shape that component emits and the shape ScheduleManagerForm reads a saved schedule back into -
 * into the `Record<string, string>` every labels-carrying API model uses (backend:
 * `Map<String, String> labels` on WorkflowSchedule/WorkflowScheduleEntity; frontend:
 * `labels?: Record<string, string>` throughout Types).
 *
 * Splits on the FIRST colon only, matching AddTaskTemplateForm's `indexOf(":")` handling of the
 * same component's output. Creatable itself rejects colons in either half ('":" is not allowed'),
 * so in practice there is exactly one; entries with no colon at all, or with an empty key, are
 * dropped rather than written as a malformed label.
 */
export function labelStringsToRecord(labels: Array<string> | undefined | null): Record<string, string> {
  if (!Array.isArray(labels)) {
    return {};
  }
  return labels.reduce((acc: Record<string, string>, pair) => {
    const separatorIndex = typeof pair === "string" ? pair.indexOf(":") : -1;
    if (separatorIndex < 1) {
      return acc;
    }
    acc[pair.slice(0, separatorIndex)] = pair.slice(separatorIndex + 1);
    return acc;
  }, {});
}

export function groupTasksByName(taskTemplates: Task[]) {
  return taskTemplates.reduce((acc: Record<string, Task[]>, task: Task) => {
    if (acc[task.name]) {
      acc[task.name].push(task);
      acc[task.name].sort((a, b) => b.version - a.version);
    } else {
      acc[task.name] = [task];
    }
    return acc;
  }, {});
}
