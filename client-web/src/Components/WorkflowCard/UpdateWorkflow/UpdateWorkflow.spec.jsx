import { vi } from "vitest";
import ImportWorkflow from ".";
import { screen, fireEvent } from "@testing-library/react";

const mockfn = vi.fn();

const props = {
  fetchTeams: mockfn,
  handleImportWorkflow: mockfn,
  workflowId: "test",
};

beforeEach(() => {
  document.body.setAttribute("id", "app");
});

describe("ImportWorkflow --- Snapshot Test", () => {
  it("Capturing Snapshot of ImportWorkflow", () => {
    const { baseElement } = rtlContextRouterRender(<ImportWorkflow {...props} />);
    fireEvent.click(screen.getByText(/Choose a file or drag one here/i));
    expect(baseElement).toMatchSnapshot();
  });
});
