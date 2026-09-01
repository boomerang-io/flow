import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { profile, workspaces } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { RunPhase, RunStatus, TaskRun, WorkflowCanvas, WorkflowRun as WorkflowRunType, WorkflowStatus } from "Types";
import { HttpMethod } from "Constants";
import WorkflowExecutionContainer, { action, loader, type RunActionIntent } from "./WorkflowRun";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";

const workspace = "tyson-workspace";
const workflowName = "test-workflow";
const runId = "5ec51eca5a92d80001a2005d";

const taskRun: TaskRun = {
  annotations: { "boomerang.io/position": { x: 0, y: 0 } },
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 300190,
  id: "5c36289096052900012cc81e",
  labels: {},
  name: "Execute Shell 1",
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
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  taskRef: "515c8b05-ceb0-470a-a58e-b8740b332a6a",
  timeout: 0,
  type: "template",
  workflowRef: workflowName,
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowRunRef: runId,
  workflowName,
  workspaces: [],
};

// The shared fixture (`ApiServer/fixtures/workflowExecution.js`) still holds a v3-era record
// (`steps`/`flowTaskStatus`, no `tasks`/`phase`), which is not this spec's file to fix (the
// fixtures stay shared across every consumer) - so override the default handlers.ts responses
// with the current-shape objects this render actually needs, the same technique
// AdminTasks.spec.tsx uses for its PUT override. `getWorkflowComposeRun` needs no route of its
// own - MSW matches on pathname only, and it shares one with `getWorkflowCompose` (see
// handlers.ts), which the override below replaces for this test.
const workflowRun: WorkflowRunType = {
  annotations: {
    "boomerang.io/task-deletion": "Never",
    "boomerang.io/task-default-image": "",
    "boomerang.io/workspace-name": "Workspace",
    "boomerang.io/kind": "WorkflowRun",
    "boomerang.io/generation": "1",
  },
  awaitingApproval: false,
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 300190,
  id: runId,
  initiatedByRef: "",
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  tasks: [taskRun],
  timeout: 0,
  trigger: "manual",
  workspaces: [],
  workflowName,
  workflowRef: workflowName,
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

const workflow: WorkflowCanvas = {
  id: "651b91a77fbb1a64ab8b7154",
  name: workflowName,
  displayName: "Test Workflow",
  creationDate: "2019-09-03T15:00:00.230+0000",
  status: WorkflowStatus.Active,
  version: 1,
  description: "",
  icon: "bot",
  tasks: [],
  changelog: {
    author: "",
    reason: "",
    date: "2019-09-03T15:00:00.230+0000",
  },
  triggers: {
    event: { enabled: false, conditions: [] },
    github: { enabled: false, conditions: [] },
    manual: { enabled: true, conditions: [] },
    schedule: { enabled: false, conditions: [] },
    webhook: { enabled: false, conditions: [] },
  },
  upgradesAvailable: false,
  workspaces: [],
  edges: [],
  nodes: [],
  config: [],
};

// jsdom has no ResizeObserver, which @xyflow/react's canvas relies on to size itself on mount -
// this is the first spec to render the real diagram (every other WorkflowRun/Editor spec never
// got past a broken mock route to reach it), so stub it locally rather than adding a global
// polyfill nothing else needs yet.
class ResizeObserverStub implements ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  global.ResizeObserver = ResizeObserverStub;
  server.use(
    http.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), () =>
      HttpResponse.json(workflowRun),
    ),
    http.get(serviceUrl.workspace.task.queryTasks({ workspace: ":workspace", query: "" }), () =>
      HttpResponse.json({ content: [] }),
    ),
    http.get(
      serviceUrl.workspace.workflow.getWorkflowComposeRun({ workspace: ":workspace", workflow: ":workflow" }),
      () => HttpResponse.json(workflow),
    ),
  );
});

describe("Execution --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowExecutionContainer", async () => {
    const { baseElement } = renderWithContext(
      // The route now carries its own `loader` (see WorkflowRun.tsx) - the same shape
      // app/routes/run.tsx wires up, and the pattern GlobalParameters.spec.tsx established.
      <Route path={AppPath.Run} loader={loader} element={<WorkflowExecutionContainer />} />,
      {
        contextValue: {
          isTutorialActive: false,
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        },
        route: appLink.execution({ workspace, runId }),
      },
    );
    await screen.findByText(/Activity detail/i);

    expect(baseElement).toMatchSnapshot();
  });
});

/*
 * The route `action` is the entire write surface of this screen - the six lifecycle transitions
 * RunHeader drives plus the approval/manual submission both task modals make - and nothing
 * rendered it before. These call it directly, the idiom AdminTasks.spec.tsx/GlobalTokens.spec.tsx
 * established for route-module actions.
 *
 * Every expectation below is built by calling the same `serviceUrl.*` builder the action's own
 * RUN_INTENT_REQUESTS table names, never a hand-copied path string, so an intent wired to the
 * wrong builder (retry -> putStartWorkflow, say) fails here rather than passing on a literal that
 * was copied from the same mistake.
 */
type CapturedRequest = { path: string; method: string; body: string };

/** Intercepts every outbound request the action makes, whatever URL/method it picks. */
function captureRequests(): Array<CapturedRequest> {
  const captured: Array<CapturedRequest> = [];
  server.use(
    http.all("*", async ({ request }) => {
      captured.push({
        path: new URL(request.url).pathname,
        method: request.method.toLowerCase(),
        body: await request.text(),
      });
      return HttpResponse.json({});
    }),
  );
  return captured;
}

function actionRequest(fields: Record<string, string>) {
  return new Request(`http://localhost${appLink.execution({ workspace, runId })}`, {
    method: "post",
    body: new URLSearchParams(fields),
  });
}

function submit(fields: Record<string, string>) {
  return action({ params: { workspace, runId }, request: actionRequest(fields) });
}

const runUrl = serviceUrl.workspace.workflowrun;

const INTENT_CONTRACT: Array<{ intent: RunActionIntent; path: string; method: string }> = [
  { intent: "retry", path: runUrl.putRetryWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
  { intent: "cancel", path: runUrl.deleteCancelWorkflow({ workspace, id: runId }), method: HttpMethod.Delete },
  { intent: "start", path: runUrl.putStartWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
  { intent: "pause", path: runUrl.putPauseWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
  { intent: "resume", path: runUrl.putResumeWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
  { intent: "finalize", path: runUrl.putFinalizeWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
];

describe("WorkflowRun --- action", () => {
  INTENT_CONTRACT.forEach(({ intent, path, method }) => {
    it(`issues a single ${method.toUpperCase()} ${intent} request to the run's ${intent} route`, async () => {
      const captured = captureRequests();

      const result = await submit({ intent });

      expect(result).toEqual({ intent });
      expect(captured).toEqual([{ path, method, body: "" }]);
    });
  });

  it("sends the approval decision as a one-element array", async () => {
    const captured = captureRequests();

    const result = await submit({
      intent: "action",
      actionId: "action-abc",
      approved: "true",
      comments: "Looks good to me",
    });

    expect(result).toEqual({ intent: "action" });
    expect(captured).toHaveLength(1);
    expect(captured[0].path).toBe(serviceUrl.workspace.action.putAction({ workspace }));
    expect(captured[0].method).toBe(HttpMethod.Put);
    expect(JSON.parse(captured[0].body)).toEqual([
      { id: "action-abc", approved: true, comments: "Looks good to me" },
    ]);
  });

  it("derives approved from the submitted string and defaults an absent comment to an empty string", async () => {
    const captured = captureRequests();

    // `approved` arrives as a form field, so it is the string "false" here, not the boolean -
    // anything other than the exact string "true" is a rejection.
    await submit({ intent: "action", actionId: "action-abc", approved: "false" });

    expect(JSON.parse(captured[0].body)).toEqual([{ id: "action-abc", approved: false, comments: "" }]);
  });

  it("rejects an unrecognised intent without issuing any request", async () => {
    const captured = captureRequests();

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await submit({ intent: "explode" })) as unknown as { data: { intent: string; error: unknown } };

    expect(result.data).toEqual({
      intent: "explode",
      error: { title: "Something's wrong", message: "Unrecognised request" },
    });
    expect(captured).toHaveLength(0);
  });

  it("returns a formatted error rather than throwing when a lifecycle call fails", async () => {
    server.use(
      http.put(runUrl.putRetryWorkflow({ workspace: ":workspace", id: ":id" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = (await submit({ intent: "retry" })) as unknown as {
      data: { intent: string; error: { title: string; message: string } };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("retry");
    expect(result.data.error.title).toEqual(expect.any(String));
    expect(result.data.error.message).toEqual(expect.any(String));
  });

  it("returns a formatted error rather than throwing when the action submission fails", async () => {
    server.use(
      http.put(serviceUrl.workspace.action.putAction({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = (await submit({ intent: "action", actionId: "action-abc", approved: "true" })) as unknown as {
      data: { intent: string; error: { message: string } };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("action");
    expect(result.data.error.message).toEqual(expect.any(String));
  });
});
