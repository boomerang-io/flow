import React from "react";
import { act, screen, waitFor } from "@testing-library/react";
import { profile, workspaces } from "ApiServer/fixtures";
import { server } from "ApiServer/msw/node";
import { http, HttpResponse } from "msw";
import { Outlet, Route, useFetcher, useNavigate, type RouteObject } from "react-router-dom";
import { shouldRevalidate as rootShouldRevalidate } from "Features/App/App";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import { HttpMethod } from "Constants";
import { AppPath, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { RunPhase, RunStatus, TaskRun, WorkflowCanvas, WorkflowRun as WorkflowRunType, WorkflowStatus } from "Types";
import WorkflowExecutionContainer, { action, loader, type RunActionIntent } from "./WorkflowRun";

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

// The start route takes an optional JSON body, so the start sends `{}`; the others send none.
const INTENT_CONTRACT: Array<{ intent: RunActionIntent; path: string; method: string; body?: string }> = [
  { intent: "cancel", path: runUrl.deleteCancelWorkflow({ workspace, id: runId }), method: HttpMethod.Delete },
  { intent: "start", path: runUrl.putStartWorkflow({ workspace, id: runId }), method: HttpMethod.Put, body: "{}" },
  { intent: "pause", path: runUrl.putPauseWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
  { intent: "resume", path: runUrl.putResumeWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
];

describe("WorkflowRun --- action", () => {
  INTENT_CONTRACT.forEach(({ intent, path, method, body = "" }) => {
    it(`issues a single ${method.toUpperCase()} ${intent} request to the run's ${intent} route`, async () => {
      const captured = captureRequests();

      const result = await submit({ intent });

      expect(result).toEqual({ intent });
      expect(captured).toEqual([{ path, method, body }]);
    });
  });

  // A bodiless PUT goes out as a form, which the start route answers with 415.
  it("sends the start as JSON", async () => {
    let contentType: string | null = null;
    server.use(
      http.all("*", ({ request }) => {
        contentType = request.headers.get("content-type");
        return HttpResponse.json({});
      }),
    );

    await submit({ intent: "start" });

    expect(contentType).toMatch(/^application\/json/);
  });

  // A retry carries its scope as the request body: no scope from the form is the whole graph,
  // and the modal's task names travel as a JSON list.
  it("issues a PUT retry request with the whole graph as its scope by default", async () => {
    const captured = captureRequests();

    const result = await submit({ intent: "retry" });

    expect(result).toMatchObject({ intent: "retry" });
    expect(captured).toEqual([
      {
        path: runUrl.putRetryWorkflow({ workspace, id: runId }),
        method: HttpMethod.Put,
        body: JSON.stringify({ scope: "all", nodes: [] }),
      },
    ]);
  });

  // The retry creates the new run unstarted; the page starts it the way its Start button does.
  it("starts the run the retry created and answers with it", async () => {
    const captured: Array<{ path: string; method: string }> = [];
    server.use(
      http.all("*", ({ request }) => {
        const path = new URL(request.url).pathname;
        captured.push({ path, method: request.method.toLowerCase() });
        return HttpResponse.json(path.endsWith("/retry") ? { id: "retried-run" } : {});
      }),
    );

    const result = await submit({ intent: "retry" });

    expect(result).toEqual({ intent: "retry", workflowRunRef: "retried-run" });
    expect(captured).toEqual([
      { path: runUrl.putRetryWorkflow({ workspace, id: runId }), method: HttpMethod.Put },
      { path: runUrl.putStartWorkflow({ workspace, id: "retried-run" }), method: HttpMethod.Put },
    ]);
  });

  it("still answers with the retried run when it fails to start, so the page can open it", async () => {
    server.use(
      http.put(runUrl.putRetryWorkflow({ workspace: ":workspace", id: ":id" }), () =>
        HttpResponse.json({ id: "retried-run" }),
      ),
      http.put(runUrl.putStartWorkflow({ workspace: ":workspace", id: ":id" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const result = (await submit({ intent: "retry" })) as unknown as {
      data: { intent: string; workflowRunRef: string; error: { message: string } };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.workflowRunRef).toBe("retried-run");
    expect(result.data.error.message).toEqual(expect.any(String));
  });

  it("sends the chosen scope and task names on a scoped retry", async () => {
    const captured = captureRequests();

    await submit({ intent: "retry", scope: "nodes", nodes: JSON.stringify(["chair"]) });

    expect(captured[0].body).toEqual(JSON.stringify({ scope: "nodes", nodes: ["chair"] }));
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
    expect(JSON.parse(captured[0].body)).toEqual([{ id: "action-abc", approved: true, comments: "Looks good to me" }]);
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

/*
 * The run page polls while the run is live. The poll must re-run this route's loader only: the
 * root bootstrap (profile, features, navigation, starters) is not the run's data and must not
 * reload every five seconds, but it must still reload after a mutation.
 */
describe("WorkflowRun --- live poll", () => {
  const POLL_MS = 5000;

  function setup() {
    const counts = { root: 0, runReads: 0 };
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), () => {
        counts.runReads += 1;
        return HttpResponse.json({ ...workflowRun, status: RunStatus.Running, phase: RunPhase.Running });
      }),
      http.delete(serviceUrl.workspace.workflowrun.deleteCancelWorkflow({ workspace: ":workspace", id: ":id" }), () =>
        HttpResponse.json({}),
      ),
    );

    function Mutate() {
      const fetcher = useFetcher();
      return (
        <button
          onClick={() =>
            fetcher.submit({ intent: "cancel" }, { method: "post", action: appLink.execution({ workspace, runId }) })
          }
        >
          Mutate
        </button>
      );
    }

    const routes: RouteObject[] = [
      {
        id: "root",
        path: "/",
        loader: () => {
          counts.root += 1;
          return { rootLoads: counts.root };
        },
        shouldRevalidate: rootShouldRevalidate,
        element: (
          <>
            <Mutate />
            <Outlet />
          </>
        ),
        children: [
          {
            path: AppPath.Run,
            loader,
            action,
            element: <WorkflowExecutionContainer />,
          },
        ],
      },
    ];
    renderWithContext(<></>, { routes, route: appLink.execution({ workspace, runId }) });
    return counts;
  }

  beforeEach(() => {
    // The shared setup freezes the clock; only the interval is faked here so MSW keeps real timers.
    vi.useRealTimers();
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function tick() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS);
    });
  }

  it("refreshes the run on every tick without re-running the root loader", async () => {
    const counts = setup();
    await screen.findByText(/Activity detail/i);
    expect(counts).toEqual({ root: 1, runReads: 1 });

    for (let n = 2; n <= 4; n++) {
      await tick();
      await waitFor(() => expect(counts.runReads).toBe(n));
    }

    expect(counts.root).toBe(1);
  });

  it("shows what the poll read: a run the poll finds finished stops the poll", async () => {
    const counts = setup();
    await screen.findByText(/Activity detail/i);
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), () => {
        counts.runReads += 1;
        return HttpResponse.json({ ...workflowRun, status: RunStatus.Succeeded, phase: RunPhase.Completed });
      }),
    );

    await tick();
    await waitFor(() => expect(counts.runReads).toBe(2));
    await tick();
    await tick();

    expect(counts.runReads).toBe(2);
    expect(counts.root).toBe(1);
  });

  // A poll that comes back with no run (an expired session, a removed member) is the one poll
  // result the root has to hear about: it is revalidated once, and with no run the poll stops.
  it("revalidates the root once when a poll fails, and stops polling", async () => {
    const counts = setup();
    await screen.findByText(/Activity detail/i);
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), () => {
        counts.runReads += 1;
        return HttpResponse.json({}, { status: 401 });
      }),
    );

    await tick();
    await waitFor(() => expect(counts.root).toBe(2));
    // The failed poll, then the one revalidation: it re-runs the run's loader and, as every
    // revalidation does, the poll's own fetcher load.
    await waitFor(() => expect(counts.runReads).toBe(4));
    await tick();
    await tick();
    await tick();

    expect(counts.root).toBe(2);
    expect(counts.runReads).toBe(4);
  });

  // A poll for run A still in flight when the page moves to run B must not put A back on screen.
  it("keeps showing run B after navigating while run A's poll is in flight", async () => {
    const otherRun = "5ec51eca5a92d80001a2ffff";
    const reads: Array<string> = [];
    let slowA = false;
    server.use(
      http.get(
        serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }),
        async ({ params }) => {
          reads.push(String(params.id));
          if (params.id === otherRun) {
            return HttpResponse.json({
              ...workflowRun,
              id: otherRun,
              status: RunStatus.Failed,
              phase: RunPhase.Completed,
            });
          }
          if (slowA) {
            await new Promise((resolve) => setTimeout(resolve, 400));
          }
          return HttpResponse.json({ ...workflowRun, status: RunStatus.Running, phase: RunPhase.Running });
        },
      ),
    );
    function GoToOtherRun() {
      const navigate = useNavigate();
      return <button onClick={() => navigate(appLink.execution({ workspace, runId: otherRun }))}>Other run</button>;
    }
    const routes: RouteObject[] = [
      {
        id: "root",
        path: "/",
        loader: () => ({}),
        element: (
          <>
            <GoToOtherRun />
            <Outlet />
          </>
        ),
        children: [{ path: AppPath.Run, loader, action, element: <WorkflowExecutionContainer /> }],
      },
    ];
    renderWithContext(<></>, { routes, route: appLink.execution({ workspace, runId }) });
    await screen.findByText(/Activity detail/i);
    const shownStatus = () => screen.getByTestId("run-status").textContent;
    const whileRunning = shownStatus();

    slowA = true;
    await tick();
    await waitFor(() => expect(reads.filter((id) => id === runId)).toHaveLength(2));
    await act(async () => {
      screen.getByText("Other run").click();
    });
    await waitFor(() => expect(reads).toContain(otherRun));
    await waitFor(() => expect(shownStatus()).not.toBe(whileRunning));
    const whenFailed = shownStatus();
    // Run A's slow poll lands after the page has moved on.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700));
    });

    expect(shownStatus()).toBe(whenFailed);
  });

  it("still re-runs the root loader after a mutation", async () => {
    const counts = setup();
    await screen.findByText(/Activity detail/i);
    expect(counts.root).toBe(1);

    await act(async () => {
      screen.getByText("Mutate").click();
    });

    await waitFor(() => expect(counts.root).toBe(2));
    await waitFor(() => expect(counts.runReads).toBe(2));
  });
});
