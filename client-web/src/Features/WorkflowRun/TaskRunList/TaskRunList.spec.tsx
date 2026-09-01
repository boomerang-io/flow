import React from "react";
import { screen, fireEvent, within } from "@testing-library/react";
import { NodeType } from "Constants";
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

// The synthetic graph markers every run carries - see NodeType.Start/End - always bookend the
// log regardless of sort direction, so they intentionally sit outside the startTime range of
// slackTask/emailTask above.
const startTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2019-09-03T14:59:00.000+0000",
  id: "5c36289096052900012cc800",
  name: "start",
  startTime: "2019-09-03T14:59:00.000+0000",
  type: NodeType.Start,
};

const endTask: TaskRun = {
  ...baseTaskRun,
  creationDate: "2019-09-03T15:02:00.000+0000",
  id: "5c36289096052900012cc8ff",
  name: "end",
  startTime: "2019-09-03T15:02:00.000+0000",
  type: NodeType.End,
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
  tasks: [startTask, slackTask, emailTask, endTask],
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
  it("Sort tasks, with START pinned first and END pinned last", () => {
    global.rtlRouterRender(<ExecutionTaskLog {...props} />);

    const sortButton = screen.getByTestId("taskbar-button");
    let taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems).toHaveLength(4);
    expect(taskItems[0]).toHaveTextContent("start");
    expect(taskItems[1]).toHaveTextContent("Email");
    expect(taskItems[2]).toHaveTextContent("Slack");
    expect(taskItems[3]).toHaveTextContent("end");

    fireEvent.click(sortButton);
    taskItems = screen.getAllByTestId("taskitem-name");
    expect(taskItems[0]).toHaveTextContent("start");
    expect(taskItems[1]).toHaveTextContent("Slack");
    expect(taskItems[2]).toHaveTextContent("Email");
    expect(taskItems[3]).toHaveTextContent("end");
  });

  it("Renders START and END slim - no start time or duration shown", () => {
    global.rtlRouterRender(<ExecutionTaskLog {...props} />);

    // Ordering is asserted separately above - index into the <li> list items directly rather
    // than walking up from the name node (keeps this Testing-Library-idiomatic).
    const listItems = screen.getAllByRole("listitem");
    expect(listItems).toHaveLength(4);
    const [startItem, emailItem, , endItem] = listItems;

    expect(within(startItem).queryByText("Start time")).not.toBeInTheDocument();
    expect(within(startItem).queryByText("Duration")).not.toBeInTheDocument();
    expect(within(endItem).queryByText("Start time")).not.toBeInTheDocument();
    expect(within(endItem).queryByText("Duration")).not.toBeInTheDocument();

    // Normal task entries are unaffected.
    expect(within(emailItem).getByText("Start time")).toBeInTheDocument();
    expect(within(emailItem).getByText("Duration")).toBeInTheDocument();
  });
});
