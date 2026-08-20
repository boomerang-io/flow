import React from "react";
import { screen, fireEvent } from "@testing-library/react";
import { RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
import ExecutionTaskLog from "./index";

const baseTaskRun = {
  annotations: { "boomerang.io/position": { x: 0, y: 0 } },
  duration: 300190,
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
  type: "template",
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowRunRef: "651e4789ab1cb56bc8976ae4",
  workflowName: "Parameter Resolution Check",
  workspaces: [],
};

const slackTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2019-09-03T15:00:00.230+0000",
  id: "5c36289096052900012cc81e",
  name: "Slack",
  results: [{ name: "args", description: "", value: "test" }],
  startTime: "2019-09-03T15:00:00.230+0000",
};

const emailTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2019-09-03T15:01:00.103+0000",
  id: "5c3628909605290001dcc81e",
  name: "Email",
  startTime: "2019-09-03T15:01:00.103+0000",
};

const workflowRun: WorkflowRun = {
  annotations: {
    "boomerang.io/task-deletion": "Never",
    "boomerang.io/task-default-image": "",
    "boomerang.io/workspace-name": "Workspace",
    "boomerang.io/kind": "WorkflowRun",
    "boomerang.io/generation": "1",
  },
  awaitingApproval: false,
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 904934,
  id: "651e4789ab1cb56bc8976ae4",
  initiatedByRef: "",
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  tasks: [slackTask, emailTask],
  timeout: 0,
  trigger: "manual",
  workspaces: [],
  workflowName: "Parameter Resolution Check",
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

const props = {
  workflowRun,
  executionViewRedirect: () => {},
};

describe("ExecutionTaskLog --- Snapshot", () => {
  it("Capturing Snapshot of ExecutionTaskLog", () => {
    const { baseElement } = global.rtlRouterRender(<ExecutionTaskLog {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("ExecutionTaskLog --- RTL", () => {
  it("Sort tasks", () => {
    global.rtlRouterRender(<ExecutionTaskLog {...props} />);

    const sortButton = screen.getByTestId("taskbar-button");
    let taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems[0]).toHaveTextContent("Email");
    expect(taskItems[1]).toHaveTextContent("Slack");

    fireEvent.click(sortButton);
    taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems[0]).toHaveTextContent("Slack");
    expect(taskItems[1]).toHaveTextContent("Email");
  });
});
