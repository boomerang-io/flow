import { RunStatus, TaskRun } from "Types";

/**
 * A task with `foreach` runs as one parent task run - named after the task, carrying the combined
 * status and results - plus one item task run per item, named `<name>[<index>]`, with `parentRef`
 * set to the parent's id. Everything the run view draws per task (a node, an edge colour, a row
 * in the task log) belongs to the parent; the items only appear inside the parent's row.
 */
export function isForeachItem(taskRun: TaskRun): boolean {
  return Boolean(taskRun.parentRef);
}

/** The task run a diagram node or edge stands for: the one named after the task, never an item. */
export function findTaskRunByName(tasks: Array<TaskRun> | undefined, name?: string): TaskRun | undefined {
  if (!name) {
    return undefined;
  }
  return tasks?.find((taskRun) => taskRun.name === name && !isForeachItem(taskRun));
}

/** The item task runs of a for-each parent, in item order. */
export function foreachItemRuns(tasks: Array<TaskRun> | undefined, parentId: string): Array<TaskRun> {
  return (tasks ?? [])
    .filter((taskRun) => taskRun.parentRef === parentId)
    .sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
}

const FAILED_STATUSES: ReadonlyArray<RunStatus> = [
  RunStatus.Failed,
  RunStatus.Cancelled,
  RunStatus.TimedOut,
  RunStatus.Invalid,
];
const RUNNING_STATUSES: ReadonlyArray<RunStatus> = [
  RunStatus.NotStarted,
  RunStatus.Ready,
  RunStatus.Running,
  RunStatus.Waiting,
];

export type ForeachSummary = { total: number; succeeded: number; running: number; failed: number };

/** Counts a for-each task's items by outcome. Running covers every item not yet ended. */
export function summarizeForeachItems(items: Array<TaskRun>): ForeachSummary {
  return items.reduce<ForeachSummary>(
    (summary, item) => {
      if (item.status === RunStatus.Succeeded) summary.succeeded += 1;
      else if (FAILED_STATUSES.includes(item.status)) summary.failed += 1;
      else if (RUNNING_STATUSES.includes(item.status)) summary.running += 1;
      return summary;
    },
    { total: items.length, succeeded: 0, running: 0, failed: 0 },
  );
}

/** "3 items · 2 succeeded · 1 failed" - running and failed are left out while they are zero. */
export function formatForeachSummary({ total, succeeded, running, failed }: ForeachSummary): string {
  const parts = [`${total} ${total === 1 ? "item" : "items"}`, `${succeeded} succeeded`];
  if (running > 0) parts.push(`${running} running`);
  if (failed > 0) parts.push(`${failed} failed`);
  return parts.join(" · ");
}

/** The item's own part of its name - `[1]` for `locate[1]`. */
export function foreachItemSuffix(item: TaskRun, parentName: string): string {
  return item.name.startsWith(parentName) ? item.name.slice(parentName.length) : item.name;
}
