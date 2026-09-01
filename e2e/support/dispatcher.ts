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
  workspaces?: { name: string; type: string; optional?: boolean; mountPath?: string }[];
  dependencies?: TaskDependency[];
};
export type WorkflowSpec = {
  name: string;
  displayName?: string;
  timeout?: number;
  params?: { name: string; type?: string; defaultValue?: unknown; description?: string }[];
  workspaces?: { name: string; type: string; optional?: boolean; spec?: Record<string, string> }[];
  tasks: WorkflowTaskSpec[];
};
export type TaskRunView = {
  id: string;
  name: string;
  type: string;
  status: string;
  phase?: string;
  statusMessage?: string;
  statusReason?: string;
  results?: { name: string; value: unknown }[];
};
export type WorkflowRunView = {
  id: string;
  status: string;
  phase?: string;
  statusMessage?: string;
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
