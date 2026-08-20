import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { profile, workspaces } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { RunPhase, RunStatus, TaskRun, WorkflowCanvas, WorkflowRun as WorkflowRunType, WorkflowStatus } from "Types";
import WorkflowExecutionContainer from "./index";

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
    const { baseElement } = global.rtlContextRouterRender(
      <Route path={AppPath.Run} element={<WorkflowExecutionContainer />} />,
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
