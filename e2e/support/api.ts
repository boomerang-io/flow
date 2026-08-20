import type { APIRequestContext } from "@playwright/test";

/*
 * Direct-to-backend setup/verification helpers, hitting service-core's real REST API
 * (/api/v2/...) through the same gateway origin the browser uses - see playwright.config.ts.
 * These exist so UI journeys can set up their own isolated fixtures (a workspace, a workflow,
 * a run) quickly and assert against the real, persisted result, instead of relying on
 * whatever state a previous test left behind.
 *
 * Security is disabled for this stack (flow.security.enabled=false - see docker-compose.yml),
 * so no auth header is sent. Once real authentication lands, this is where a session/token
 * would be attached.
 */

export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

export type Workspace = { name: string; displayName: string };
export type Workflow = { id: string; name: string; displayName: string };
export type WorkflowRun = { id: string; status: string; phase?: string };

export async function createWorkspace(request: APIRequestContext, displayName: string): Promise<Workspace> {
  const res = await request.post("/api/v2/workspace", {
    data: { name: displayName, displayName },
  });
  if (!res.ok()) {
    throw new Error(`createWorkspace(${displayName}) failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function createWorkflow(
  request: APIRequestContext,
  workspace: string,
  displayName: string,
): Promise<Workflow> {
  const res = await request.post(`/api/v2/workspace/${workspace}/workflow`, {
    data: { name: displayName, displayName, tasks: [] },
  });
  if (!res.ok()) {
    throw new Error(`createWorkflow(${workspace}, ${displayName}) failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function submitWorkflowRun(
  request: APIRequestContext,
  workspace: string,
  workflowName: string,
): Promise<WorkflowRun> {
  const res = await request.post(`/api/v2/workspace/${workspace}/workflow/${workflowName}/submit?start=false`, {
    data: {},
  });
  if (!res.ok()) {
    throw new Error(`submitWorkflowRun(${workspace}, ${workflowName}) failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function getWorkspace(request: APIRequestContext, workspace: string): Promise<Workspace> {
  const res = await request.get(`/api/v2/workspace/${workspace}`);
  if (!res.ok()) {
    throw new Error(`getWorkspace(${workspace}) failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

// Mirrors client-web/src/Constants/index.ts's ExecutionStatusCopy - the UI's human-readable
// label for each RunStatus. Kept in sync manually; if it drifts, the "view a run" journey
// below will fail loudly rather than silently pass on stale text.
export const executionStatusCopy: Record<string, string> = {
  cancelled: "Cancelled",
  succeeded: "Succeeded",
  failed: "Failed",
  running: "Running",
  notstarted: "Not Started",
  invalid: "Invalid",
  skipped: "Skipped",
  waiting: "Waiting",
  ready: "Ready",
  timedout: "Timed Out",
};
