import { NodeType } from "Constants";
import { RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";

/**
 * A run of `stage -> locate (for each) -> end` caught mid fan-out: `locate` is the for-each
 * parent, and its three items are one succeeded, one still running and one failed (OOMKilled).
 * The items are listed before their parent, as nothing guarantees the order of the `tasks` list.
 */
const baseTaskRun = {
  annotations: { "boomerang.io/position": { x: 0, y: 0 } },
  duration: 0,
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  spec: {
    arguments: null,
    command: null,
    debug: false,
    deletion: null,
    envs: null,
    image: null,
    timeout: 0,
    script: null,
    workingDir: null,
  },
  status: RunStatus.Succeeded,
  statusMessage: "",
  taskRef: "515c8b05-ceb0-470a-a58e-b8740b332a6a",
  timeout: 0,
  type: NodeType.Template,
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowRunRef: "651e4789ab1cb56bc8976f00",
  workflowName: "Locate Everything",
  workspaces: [],
};

export const foreachStartTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:00.000+0000",
  id: "foreach-start",
  name: "start",
  startTime: "2026-09-26T10:00:00.000+0000",
  type: NodeType.Start,
};

export const foreachStageTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:01.000+0000",
  duration: 4000,
  id: "foreach-stage",
  name: "stage",
  startTime: "2026-09-26T10:00:01.000+0000",
};

export const foreachParentTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:05.000+0000",
  id: "foreach-locate",
  name: "locate",
  phase: RunPhase.Running,
  startTime: "2026-09-26T10:00:05.000+0000",
  status: RunStatus.Running,
};

function item(index: number, value: string, overrides: Partial<TaskRun>): TaskRun {
  return {
    ...baseTaskRun,
    creationDate: "2026-09-26T10:00:05.000+0000",
    id: `foreach-locate-${index}`,
    index,
    name: `locate[${index}]`,
    params: [
      { name: "item", value },
      { name: "index", value: String(index) },
    ],
    parentRef: foreachParentTask.id,
    startTime: `2026-09-26T10:00:0${6 + index}.000+0000`,
    ...overrides,
  };
}

export const foreachItemTasks: Array<TaskRun> = [
  item(2, "repo-c", {
    duration: 12000,
    status: RunStatus.Failed,
    statusMessage: "Container exceeded its memory limit",
    statusReason: "OOMKilled",
  }),
  item(0, "repo-a", { duration: 3000, status: RunStatus.Succeeded }),
  item(1, "repo-b", { phase: RunPhase.Running, status: RunStatus.Running }),
];

export const foreachEndTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:30.000+0000",
  id: "foreach-end",
  name: "end",
  phase: RunPhase.Pending,
  startTime: "2026-09-26T10:00:30.000+0000",
  status: RunStatus.NotStarted,
  type: NodeType.End,
};

export const foreachWorkflowRun: WorkflowRun = {
  annotations: {
    "boomerang.io/task-deletion": "Never",
    "boomerang.io/task-default-image": "",
    "boomerang.io/workspace-name": "Workspace",
    "boomerang.io/kind": "WorkflowRun",
    "boomerang.io/generation": "1",
  },
  awaitingApproval: false,
  creationDate: "2026-09-26T10:00:00.000+0000",
  duration: 0,
  id: "651e4789ab1cb56bc8976f00",
  initiatedByRef: "",
  labels: {},
  params: [],
  phase: RunPhase.Running,
  results: [],
  retries: 0,
  startTime: "2026-09-26T10:00:00.000+0000",
  status: RunStatus.Running,
  statusMessage: "",
  tasks: [foreachStartTask, foreachStageTask, ...foreachItemTasks, foreachParentTask, foreachEndTask],
  timeout: 0,
  trigger: "manual",
  workspaces: [],
  workflowName: "Locate Everything",
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

/**
 * Two for-each parents that never fanned out, so no item task runs exist and the TaskRun alone
 * cannot say it is for-each: `scan` resolved to an empty array and succeeded at once; `fetch`
 * resolved to something other than a JSON array and failed with a typed reason.
 */
export const foreachEmptyParentTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:10.000+0000",
  id: "foreach-scan",
  name: "scan",
  startTime: "2026-09-26T10:00:10.000+0000",
};

export const foreachInvalidParentTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2026-09-26T10:00:12.000+0000",
  id: "foreach-fetch",
  name: "fetch",
  startTime: "2026-09-26T10:00:12.000+0000",
  status: RunStatus.Failed,
  statusMessage: "The for-each items did not resolve to a JSON array.",
  statusReason: "ForeachItemsInvalid",
};

export const foreachWithoutItemsWorkflowRun: WorkflowRun = {
  ...foreachWorkflowRun,
  id: "651e4789ab1cb56bc8976f01",
  phase: RunPhase.Completed,
  status: RunStatus.Failed,
  tasks: [foreachStartTask, foreachStageTask, foreachEmptyParentTask, foreachInvalidParentTask, foreachEndTask],
};
