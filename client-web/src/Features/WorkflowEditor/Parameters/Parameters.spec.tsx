import React from "react";
import { screen, fireEvent } from "@testing-library/react";
import Inputs from ".";
import { WorkflowCanvas, WorkflowStatus } from "Types";

const workflow: WorkflowCanvas = {
  id: "123",
  name: "test-workflow",
  displayName: "Test Workflow",
  creationDate: "2019-09-03T15:00:00.230+0000",
  status: WorkflowStatus.Active,
  version: 1,
  description: "",
  icon: "",
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
  config: [
    {
      id: "tim-parameter",
      default: "pandas",
      defaultValue: "pandas",
      description: "Tim parameter",
      name: "tim-parameter",
      label: "Tim parameter",
      required: true,
      type: "select",
      value: "pandas",
      options: [
        { key: "pandas", value: "pandas" },
        { key: "dogs", value: "dogs" },
      ],
    },
  ],
};

const props = {
  workflow,
  handleUpdateParams: () => {},
};

beforeEach(() => {
  document.body.setAttribute("id", "app");
});

describe("Inputs --- Snapshot Test", () => {
  it("Capturing Snapshot of Inputs", async () => {
    const { baseElement } = global.rtlContextRouterRender(<Inputs {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("Render inputs correctly", async () => {
    global.rtlContextRouterRender(<Inputs {...props} />);
    expect(screen.getByText("tim-parameter")).toBeInTheDocument();
  });

  it("Opens create new parameter modal", async () => {
    global.rtlContextRouterRender(<Inputs {...props} />);

    const modalTrigger = screen.getByTestId("create-parameter-button");
    fireEvent.click(modalTrigger);

    expect(screen.getByText(/Create a new parameter/i)).toBeInTheDocument();
  });

  it("Opens edit parameter modal", async () => {
    global.rtlContextRouterRender(<Inputs {...props} />);

    const modalTrigger = screen.getByLabelText(/Edit/i);
    fireEvent.click(modalTrigger);

    expect(screen.getByText(/Let's change some stuff/i)).toBeInTheDocument();
  });
});
