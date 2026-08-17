import { vi } from "vitest";
import WorkflowsHeader from "./index";

const mockfn = vi.fn();

const props = {
  workflowsLength: 1,
  workspacesQuery: [],
  handleSearchFilter: mockfn,
  isLoading: false,
  options: [
    {
      name: "test workspace",
      id: "testid",
    },
  ],
};

describe("WorkflowsHeader --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHeader", () => {
    const { baseElement } = rtlContextRouterRender(<WorkflowsHeader {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
