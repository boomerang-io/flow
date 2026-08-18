import { vi } from "vitest";
import { FlowWorkspaceStatus } from "Constants";
import CreateWorkflowContent from ".";

// import { screen, fireEvent } from "@testing-library/react";

const mockfn = vi.fn();
const props = {
  template: {
    name: "test-template",
    displayName: "test template",
    icon: "bot",
    description: "A test template",
    creationDate: new Date().toISOString(),
    markdown: "",
    version: 1,
    tasks: [],
    changelog: { author: "test", reason: "created", date: new Date().toISOString() },
    config: [],
  },
  createError: null,
  createWorkflow: mockfn,
  isLoading: false,
  workspaces: [
    {
      name: "test",
      displayName: "Test",
      creationDate: new Date().toISOString(),
      status: FlowWorkspaceStatus.Active,
      insights: { workflows: 0, members: 0 },
    },
  ],
};

describe("CreateWorkflowContent --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflowContent", () => {
    const { baseElement } = global.rtlRender(<CreateWorkflowContent {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
