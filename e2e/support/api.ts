import type { APIRequestContext } from "@playwright/test";

/*
 * Direct-to-backend setup/verification helpers, hitting service-core's real REST API
 * (/api/v2/...) through the same gateway origin the browser uses - see playwright.config.ts.
 * These exist so UI journeys can set up their own isolated fixtures (a workspace, a workflow,
 * a run) quickly and assert against the real, persisted result, instead of relying on
 * whatever state a previous test left behind.
 *
 * The stack is secured (FLOW_SECURITY_ENABLED=true - see docker-compose.yml). No auth header
 * is attached here because none is needed: the login bootstrap (tests/auth.setup.ts) saves the
 * httpOnly flow_session cookie into the shared storage state, and Playwright's `request`
 * fixture carries that cookie on every call these helpers make.
 */

export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

// The UI derives resource names from display names with lodash's kebabCase (e.g. Home.tsx /
// WorkflowCreate), which splits letter<->digit boundaries: "e2e-thing" becomes "e-2-e-thing",
// NOT "e2e-thing". Specs asserting on UI-derived names must use the same transformation - a
// plain toLowerCase() drifts on any digit-containing name (like every uniqueName above). This
// mirrors lodash for the names this suite generates (lowercase word/digit runs joined by "-").
export function uiKebabName(displayName: string): string {
  return (displayName.match(/[a-zA-Z]+|\d+/g) ?? []).map((part) => part.toLowerCase()).join("-");
}

// The webapp's SSR server always mounts the app under this basename (client-web's
// react-router.config.ts: `basename: "/apps/flow"`, baked into the server build at build time -
// it does not follow docker-compose.yml's APP_ROOT env var, which only feeds
// window._SERVER_DATA for the browser bundle's own API/asset base URLs, not the router's
// server-side match). The gateway (docker/gateway/nginx.conf) proxies "/" straight through with
// no rewrite, so every UI page.goto() in this suite must include this prefix or the SSR handler
// 404s. The REST API is unaffected - nginx proxies /api/ directly to service-core - so the
// request calls below stay unprefixed.
export const APP_BASENAME = "/apps/flow";

export type Workspace = { name: string; displayName: string };
export type Workflow = { id: string; name: string; displayName: string };
export type WorkflowRun = { id: string; status: string; phase?: string };

// The signed-in caller's profile - used to self-include the caller as a workspace member below.
export async function currentUser(request: APIRequestContext): Promise<{ id: string; email: string }> {
  const res = await request.get("/api/v2/profile");
  if (!res.ok()) {
    throw new Error(`GET /api/v2/profile failed: ${res.status()} ${await res.text()}`);
  }
  return res.json();
}

export async function createWorkspace(request: APIRequestContext, displayName: string): Promise<Workspace> {
  // Secured-stack contract: WorkspaceService.create writes MEMBER_OF edges ONLY from
  // request.members - the creator is NOT self-added (a reported backend gap: a session-scoped
  // user who omits members creates a workspace they then cannot see, because the relationship
  // walk anchors at the user and finds no edge). Until that is ruled/fixed, the caller must
  // include itself explicitly, exactly as the UI's create flow does.
  const me = await currentUser(request);
  const res = await request.post("/api/v2/workspace", {
    data: { name: displayName, displayName, members: [{ id: me.id, email: me.email, role: "owner" }] },
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
  // A minimal RUNNABLE graph, not tasks: [] - DAGUtility.validateWorkflow deliberately rejects a
  // workflow whose only tasks are start/end ("cant run"), and submit on such a workflow persists
  // an `invalid` run and then throws HTTP 500 (WorkflowExecutionService.queue) rather than
  // returning that run. The `sleep` template task is seeded by service-loader, and with
  // ?start=false the run parks at `ready` without needing any agent.
  const res = await request.post(`/api/v2/workspace/${workspace}/workflow`, {
    data: {
      name: displayName,
      displayName,
      tasks: [
        { name: "start", type: "start" },
        {
          name: "sleep-1",
          type: "template",
          taskRef: "sleep",
          params: [{ name: "duration", value: "1" }],
          dependencies: [{ taskRef: "start" }],
        },
        { name: "end", type: "end", dependencies: [{ taskRef: "sleep-1" }] },
      ],
    },
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
