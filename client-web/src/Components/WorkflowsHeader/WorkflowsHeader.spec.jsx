import { vi } from "vitest";
import WorkflowsHeader from "./index";

const mockfn = vi.fn();

const props = {
  workflowsLength: 1,
  teamsQuery: [],
  handleSearchFilter: mockfn,
  isLoading: false,
  options: [
    {
      name: "test team",
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
