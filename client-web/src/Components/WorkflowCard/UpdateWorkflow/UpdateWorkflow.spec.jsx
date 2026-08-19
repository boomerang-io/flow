import { vi } from "vitest";
import UpdateWorkflow from ".";
import { screen } from "@testing-library/react";
import { WorkflowView } from "Constants";

const mockfn = vi.fn();

const props = {
  workspaceName: "tyson-workspace",
  workflowRef: "test-workflow",
  getWorkflowsUrl: "/workflow/query",
  onCloseModal: mockfn,
  type: WorkflowView.Workflow,
};

beforeEach(() => {
  document.body.setAttribute("id", "app");
});

describe("UpdateWorkflow --- Snapshot Test", () => {
  it("Capturing Snapshot of UpdateWorkflow", () => {
    const { baseElement } = rtlContextRouterRender(<UpdateWorkflow {...props} />);
    expect(screen.getByRole("button", { name: /Choose a file or drag one here/i })).toBeInTheDocument();
    expect(baseElement).toMatchSnapshot();
  });
});
