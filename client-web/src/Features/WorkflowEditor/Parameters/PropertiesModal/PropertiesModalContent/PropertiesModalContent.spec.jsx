import { vi } from "vitest";
import Inputs from ".";
import { screen, fireEvent } from "@testing-library/react";

const mockfn = vi.fn();

const props = {
  isEdit: true,
  property: {
    defaultValue: "dogs",
    description: "Tim property",
    key: "tim-property",
    label: "Tim Property",
    required: false,
    type: "text",
  },
  loading: false,
  updateInputs: mockfn,
  setShouldConfirmModalClose: mockfn,
  closeModal: mockfn,
  inputsName: [],
  workflowActions: { updateWorkflowInput: mockfn, createWorkflowInput: mockfn },
  updateWorkflowProperties: mockfn,
};

describe("Inputs --- Snapshot Test", () => {
  it("Capturing Snapshot of Inputs", async () => {
    const { baseElement } = rtlContextRouterRender(<Inputs {...props} />);

    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("Change default value by type correctly", async () => {
    rtlContextRouterRender(<Inputs {...props} />);
    expect(screen.getByTestId("text-input")).toBeInTheDocument();

    const typeSelect = screen.getByPlaceholderText("Select an item");

    fireEvent.change(typeSelect, { target: { value: "b" } });

    fireEvent.click(screen.getByText(/boolean/i));

    expect(screen.queryByTestId("text-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("toggle")).toBeInTheDocument();

    fireEvent.change(typeSelect, { target: { value: "are" } });

    fireEvent.click(screen.getByText(/text area/i));

    expect(screen.queryByTestId("toggle")).not.toBeInTheDocument();
    expect(screen.getByTestId("text-area")).toBeInTheDocument();

    fireEvent.change(typeSelect, { target: { value: "sel" } });

    fireEvent.click(screen.getByText(/select/i));

    expect(screen.queryByTestId("text-area")).not.toBeInTheDocument();
    expect(screen.getByTestId("select")).toBeInTheDocument();
  });

  it("Shouldn't save parameter without key, label and type defined", async () => {
    rtlContextRouterRender(<Inputs {...props} isEdit={false} input={undefined} />);

    const keyInput = screen.getByLabelText("Key");
    const labelInput = screen.getByLabelText("Label");
    const typeSelect = screen.getByPlaceholderText("Select an item");

    fireEvent.change(keyInput, { target: { value: "test" } });

    fireEvent.change(labelInput, { target: { value: "test" } });

    fireEvent.change(typeSelect, { target: { value: "b" } });

    fireEvent.click(screen.getByText(/boolean/i));

    const createButton = await screen.findByRole("button", { name: /create/i });
    expect(createButton).toBeEnabled();
  });
});
