import React from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NodeType, PASSWORD_CONSTANT } from "Constants";
import { RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
import { renderWithRouter } from "Utils/testing/render";
import TaskItem from "./index";

const taskRun: TaskRun = {
  annotations: { "boomerang.io/position": { x: 0, y: 0 } },
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 300190,
  id: "5c36289096052900012cc81e",
  labels: {},
  name: "Send Slack Message",
  params: [],
  phase: RunPhase.Completed,
  results: [{ name: "args", description: "", value: "test" }],
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
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowRunRef: "651e4789ab1cb56bc8976ae4",
  workflowName: "Parameter Resolution Check",
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
  duration: 300190,
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
  tasks: [taskRun],
  timeout: 0,
  trigger: "manual",
  workflowName: "Parameter Resolution Check",
  workflowRef: "651b91a77fbb1a64ab8b7154",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

const props = {
  taskRun,
  workflowRun,
  executionViewRedirect: () => {},
};

describe("TaskItem --- Snapshot", () => {
  it("Capturing Snapshot of TaskItem", () => {
    const { baseElement } = renderWithRouter(<TaskItem {...props} />);
    expect(baseElement).toMatchSnapshot();
  });

  it("Capturing Snapshot of a slim (START/END) TaskItem", () => {
    const { baseElement } = renderWithRouter(<TaskItem {...props} taskRun={{ ...taskRun, type: NodeType.Start }} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("TaskItem --- RTL", () => {
  it("Renders START/END task types slim, without start time or duration", () => {
    renderWithRouter(<TaskItem {...props} taskRun={{ ...taskRun, type: NodeType.End }} />);

    expect(screen.queryByText("Start time")).not.toBeInTheDocument();
    expect(screen.queryByText("Duration")).not.toBeInTheDocument();
  });

  it("Renders a normal task type with its start time and duration as one sentence", () => {
    renderWithRouter(<TaskItem {...props} />);

    expect(screen.getByTestId("taskitem-time")).toHaveTextContent(/^Started at .+ in .+$/);
  });

  it("Masks a secret param in the details and renders the other types", async () => {
    const withParams: TaskRun = {
      ...taskRun,
      params: [
        { name: "dbPassword", value: "*****", type: "secret" },
        { name: "emptySecret", value: "", type: "secret" },
        { name: "greeting", value: "hello", type: "string" },
        { name: "untyped", value: "plain" },
      ],
    };
    renderWithRouter(<TaskItem {...props} taskRun={withParams} />);

    await userEvent.click(screen.getByRole("button", { name: "View Details" }));

    expect(await screen.findByText("dbPassword")).toBeInTheDocument();
    expect(screen.getAllByText(PASSWORD_CONSTANT)).toHaveLength(2);
    expect(screen.getByText("hello")).toBeInTheDocument();
    expect(screen.getByText("plain")).toBeInTheDocument();
  });
});

// A scoped retry starts from a task row, and marks the rows it carried over from the origin.
describe("TaskItem --- Retry from", () => {
  it("offers 'Retry from here' on a finished task when the page can open the retry modal", () => {
    const onRetryFrom = vi.fn();
    renderWithRouter(<TaskItem {...props} onRetryFrom={onRetryFrom} />);

    screen.getByTestId("retry-from-task").click();

    expect(onRetryFrom).toHaveBeenCalledWith("Send Slack Message");
  });

  it("offers nothing without a retry handler, on START/END, or on a task still running", () => {
    const { unmount } = renderWithRouter(<TaskItem {...props} />);
    expect(screen.queryByTestId("retry-from-task")).not.toBeInTheDocument();
    unmount();

    const view = renderWithRouter(<TaskItem {...props} onRetryFrom={() => {}} taskRun={{ ...taskRun, type: NodeType.Start }} />);
    expect(screen.queryByTestId("retry-from-task")).not.toBeInTheDocument();
    view.unmount();

    renderWithRouter(
      <TaskItem {...props} onRetryFrom={() => {}} taskRun={{ ...taskRun, status: RunStatus.Running, phase: RunPhase.Running }} />,
    );
    expect(screen.queryByTestId("retry-from-task")).not.toBeInTheDocument();
  });

  it("marks a task run the retry carried over as reused", () => {
    renderWithRouter(<TaskItem {...props} taskRun={{ ...taskRun, reusedFromRef: "5c36289096052900012cc800" }} />);

    expect(screen.getByText("Reused")).toBeInTheDocument();
  });
});
