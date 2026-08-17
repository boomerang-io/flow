import { vi } from "vitest";
import CreateWorkflowContent from ".";

// import { screen, fireEvent } from "@testing-library/react";

const mockfn = vi.fn();
const props = {
  createWorkflow: mockfn,
  isLoading: false,
  names: [],
  teams: [{ value: "test", label: "Test" }],
  scope: "user",
  formData: {
    selectedWorkflow: {
      name: "test template",
      icon: "bot",
    },
  },
};

describe("CreateWorkflowContent --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflowContent", () => {
    const { baseElement } = global.rtlRender(<CreateWorkflowContent {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
