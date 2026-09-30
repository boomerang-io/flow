import React from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithContext } from "Utils/testing/render";
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
    const { baseElement } = renderWithContext(<Inputs {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("lists each parameter by name with its label, type and a Required tag", async () => {
    renderWithContext(<Inputs {...props} />);
    expect(screen.getByText("tim-parameter")).toBeInTheDocument();
    expect(screen.getAllByText("Tim parameter")).toHaveLength(2); // label and description
    expect(screen.getByText("Select")).toBeInTheDocument();
    expect(screen.getByText("Required")).toBeInTheDocument();
  });

  it("opens the add parameter modal from the toolbar", async () => {
    renderWithContext(<Inputs {...props} />);

    fireEvent.click(screen.getByRole("button", { name: /Add parameter/ }));

    expect(await screen.findByText("Add parameter", { selector: "h2, h3" })).toBeInTheDocument();
  });

  it("opens the edit modal from the row menu, with the name shown as its reference", async () => {
    renderWithContext(<Inputs {...props} />);

    fireEvent.click(screen.getByRole("button", { name: "Parameter actions" }));
    fireEvent.click(await screen.findByText("Edit"));

    expect(await screen.findByText("Edit parameter", { selector: "h2, h3" })).toBeInTheDocument();
    expect(screen.getByText(/Tasks read it as \$\(params\.tim-parameter\)/)).toBeInTheDocument();
  });

  it("filters the table from the search box", async () => {
    renderWithContext(<Inputs {...props} />);

    fireEvent.change(screen.getByPlaceholderText("Search parameters"), { target: { value: "nothing-like-it" } });

    expect(await screen.findByText("No matching parameters")).toBeInTheDocument();
  });
});
