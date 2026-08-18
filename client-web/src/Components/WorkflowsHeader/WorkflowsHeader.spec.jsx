import { vi } from "vitest";
import { workspaces, workflows } from "ApiServer/fixtures";
import { WorkflowView } from "Constants";
import WorkflowsHeader from "./index";

const mockfn = vi.fn();

const props = {
  title: "Workflows",
  subtitle: "Your playground to create, execute, and collaborate on workflows.",
  handleUpdateFilter: mockfn,
  searchQuery: "",
  workspace: workspaces.content[0],
  workflowList: workflows.content,
  viewType: WorkflowView.Workflow,
};

describe("WorkflowsHeader --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHeader", () => {
    const { baseElement } = rtlContextRouterRender(<WorkflowsHeader {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
