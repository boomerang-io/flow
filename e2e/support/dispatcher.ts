import type { APIRequestContext } from "@playwright/test";

/*
 * Helpers for the dispatcher-backed scenarios (tests/dispatcher-kube.spec.ts): create a workflow
 * from an explicit task graph, submit it with start=true so the engine admits it immediately, and
 * poll the run until it reaches a terminal status. Everything goes straight to service-core's
 * REST API on API_ORIGIN, authenticated by the flow_session cookie the auth.setup project saved.
 */

const API_ORIGIN = process.env.E2E_API_URL ?? "http://localhost:7700";

export type TaskDependency = { taskRef: string; decisionCondition?: string; executionCondition?: string };
export type WorkflowTaskSpec = {
  name: string;
  type: string;
  taskRef?: string;
  taskVersion?: number;
  timeout?: number;
  params?: { name: string; value: unknown }[];
  results?: { name: string; description?: string }[];
  // Absent or true mounts the run's workspace; false opts this task out.
  workspace?: boolean;
  dependencies?: TaskDependency[];
  // Run the task once per item: a JSON array literal or one reference.
  foreach?: { items: unknown };
};
export type WorkflowSpec = {
  name: string;
  displayName?: string;
  timeout?: number;
  // A workflow param's default is `default` on the wire (AbstractParam).
  params?: { name: string; type?: string; default?: unknown; description?: string }[];
  // The one shared filesystem every task of a run can mount; absent means none.
  workspace?: { size?: string; mountPath?: string; className?: string; accessMode?: string };
  // The switches that let a run start: absent ones keep their defaults (Manual on, the rest off).
  triggers?: WorkflowTriggers;
  tasks: WorkflowTaskSpec[];
};
export type WorkflowTriggers = Partial<Record<"manual" | "schedule" | "webhook" | "event" | "github", { enabled: boolean }>>;
export type TaskRunView = {
  id: string;
  name: string;
  type: string;
  status: string;
  phase?: string;
  statusMessage?: string;
  statusReason?: string;
  results?: { name: string; value: unknown }[];
  params?: { name: string; value: unknown }[];
  startTime?: string;
  duration?: number;
  // Set on the items of a for-each task: the parent task run's id and the item's position.
  parentRef?: string;
  index?: number;
  // Set on a task a scoped retry carried over: the origin run's task run whose results it reuses.
  reusedFromRef?: string;
};
export type WorkflowRunView = {
  id: string;
  status: string;
  phase?: string;
  statusMessage?: string;
  trigger?: string;
  // The run that started this one: the origin run of a retry, the firing schedule's id.
  initiatedByRef?: string;
  paused?: boolean;
  params?: { name: string; value: unknown }[];
  results?: { name: string; value: unknown }[];
  tasks?: TaskRunView[];
};

const TERMINAL = new Set(["succeeded", "failed", "cancelled", "timedout", "invalid", "skipped"]);

export async function createWorkflowFromSpec(
  request: APIRequestContext,
  workspace: string,
  spec: WorkflowSpec,
): Promise<{ id: string; name: string }> {
  const body = { displayName: spec.name, ...spec };
  const res = await request.post(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow`, { data: body });
  if (!res.ok()) {
    throw new Error(`create workflow ${spec.name} failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function submitAndStart(
  request: APIRequestContext,
  workspace: string,
  workflowName: string,
  params: { name: string; value: unknown }[] = [],
): Promise<WorkflowRunView> {
  const res = await request.post(
    `${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${workflowName}/submit?start=true`,
    { data: { params } },
  );
  if (!res.ok()) {
    throw new Error(`submit ${workflowName} failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function getRun(
  request: APIRequestContext,
  workspace: string,
  runId: string,
): Promise<WorkflowRunView> {
  const res = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${runId}?withTasks=true`);
  if (!res.ok()) {
    throw new Error(`get run ${runId} failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

/** Polls until `until` holds for the run, or throws after timeoutMs with the last view. */
export async function waitForRunState(
  request: APIRequestContext,
  workspace: string,
  runId: string,
  until: (run: WorkflowRunView) => boolean,
  timeoutMs = 60_000,
  intervalMs = 2_000,
): Promise<WorkflowRunView> {
  const deadline = Date.now() + timeoutMs;
  let last: WorkflowRunView | undefined;
  while (Date.now() < deadline) {
    last = await getRun(request, workspace, runId);
    if (until(last)) {
      return last;
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`run ${runId} did not reach the expected state in ${timeoutMs} ms: ${describe(last)}`);
}

/** Polls until the run's status is terminal, or throws after timeoutMs with the last view. */
export async function waitForRun(
  request: APIRequestContext,
  workspace: string,
  runId: string,
  timeoutMs = 4 * 60_000,
  intervalMs = 3_000,
): Promise<WorkflowRunView> {
  const deadline = Date.now() + timeoutMs;
  let last: WorkflowRunView | undefined;
  while (Date.now() < deadline) {
    last = await getRun(request, workspace, runId);
    if (TERMINAL.has(last.status)) {
      return last;
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`run ${runId} not terminal after ${timeoutMs} ms: ${describe(last)}`);
}

/** One-line description of a run and its tasks for assertion messages. */
export function describe(run?: WorkflowRunView): string {
  if (!run) return "(no run)";
  const tasks = (run.tasks ?? [])
    .map((t) => `${t.name}=${t.status}${t.statusReason ? `/${t.statusReason}` : ""}${t.statusMessage ? ` "${t.statusMessage}"` : ""}`)
    .join(", ");
  return `run ${run.id} status=${run.status} phase=${run.phase ?? "?"}${run.statusMessage ? ` "${run.statusMessage}"` : ""} [${tasks}]`;
}

export function task(run: WorkflowRunView, name: string): TaskRunView {
  const t = (run.tasks ?? []).find((x) => x.name === name);
  if (!t) throw new Error(`task ${name} not in ${describe(run)}`);
  return t;
}

export function result(t: TaskRunView, name: string): unknown {
  return (t.results ?? []).find((r) => r.name === name)?.value;
}

export async function taskLog(request: APIRequestContext, taskRunId: string): Promise<string> {
  const res = await request.get(`${API_ORIGIN}/api/v2/taskrun/${taskRunId}/log`);
  return res.ok() ? res.text() : `(log ${res.status()})`;
}

/**
 * Sets the workflow's trigger switches by applying its current definition with them changed, the
 * same PUT the editor's Configure save sends.
 */
export async function setWorkflowTriggers(
  request: APIRequestContext,
  workspace: string,
  workflowName: string,
  triggers: WorkflowTriggers,
): Promise<void> {
  const current = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${workflowName}`);
  if (!current.ok()) {
    throw new Error(`get workflow ${workflowName} failed: ${current.status()} ${await current.text()}`);
  }
  const workflow = (await current.json()) as { triggers?: WorkflowTriggers };
  const res = await request.put(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow`, {
    data: { ...workflow, triggers: { ...workflow.triggers, ...triggers } },
  });
  if (!res.ok()) {
    throw new Error(`apply workflow ${workflowName} failed: ${res.status()} ${await res.text()}`);
  }
}

/*
 * Task builders shared by the run scenarios. A shell task runs on the catalogue's execute-shell
 * template and writes its results to $RESULTS_PATH as one JSON object (see dispatcher-kube.spec.ts
 * for why). An omitted executionCondition means `always`, which lets a failed task's successors
 * run, so a scenario that needs the run to fail with its task gates `end` with endOnSuccessOf.
 * A sleep task is the engine's own: its duration is in milliseconds and it needs no dispatcher.
 */
export const startTask: WorkflowTaskSpec = { name: "start", type: "start" };

export function shellTask(
  name: string,
  script: string,
  extra: Partial<WorkflowTaskSpec> = {},
  deps: TaskDependency[] = [{ taskRef: "start" }],
): WorkflowTaskSpec {
  return {
    name,
    type: "script",
    taskRef: "execute-shell",
    params: [
      { name: "shell", value: "sh" },
      { name: "script", value: script },
    ],
    dependencies: deps,
    ...extra,
  };
}

export function sleepTask(name: string, durationMs: number, after = "start"): WorkflowTaskSpec {
  return {
    name,
    type: "sleep",
    taskRef: "sleep",
    params: [{ name: "duration", value: String(durationMs) }],
    dependencies: [{ taskRef: after }],
  };
}

export function endAfter(...deps: string[]): WorkflowTaskSpec {
  return { name: "end", type: "end", dependencies: deps.map((taskRef) => ({ taskRef })) };
}

export function endOnSuccessOf(...deps: string[]): WorkflowTaskSpec {
  return {
    name: "end",
    type: "end",
    dependencies: deps.map((taskRef) => ({ taskRef, executionCondition: "success" })),
  };
}

/** A shell line writing one result, for a value with no quotes in it. */
export function writeResult(name: string, value: string): string {
  return `echo '{"${name}":"${value}"}' > "$RESULTS_PATH"`;
}
