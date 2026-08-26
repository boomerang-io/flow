import { vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { DataDrivenInput } from "Types";
import Inputs from ".";

const mockfn = vi.fn();

const property: DataDrivenInput = {
  id: "tim-property",
  name: "tim-property",
  label: "Tim Property",
  description: "Tim property",
  required: false,
  type: "text",
  default: "dogs",
  defaultValue: "dogs",
  value: "dogs",
};

const props = {
  isEdit: true,
  property,
  propertyKeys: [],
  closeModal: mockfn,
  updateWorkflowProperties: mockfn,
};

describe("Inputs --- Snapshot Test", () => {
  it("Capturing Snapshot of Inputs", async () => {
    const { baseElement } = global.rtlContextRouterRender(<Inputs {...props} />);

    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("Change default value by type correctly", async () => {
    global.rtlContextRouterRender(<Inputs {...props} />);
    expect(screen.getByTestId("text-input")).toBeInTheDocument();

    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    // Carbon's ComboBox (1.75) no longer surfaces its filtered option list on a bare
    // fireEvent.change - open the list with a click and select the option directly, the
    // interaction pattern the rest of this suite already uses (see Activity.spec.tsx).
    userEvent.click(typeSelect);
    userEvent.click(screen.getByText("Boolean"));

    expect(screen.queryByTestId("text-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("toggle")).toBeInTheDocument();

    userEvent.click(typeSelect);
    userEvent.click(screen.getByText("Text Area"));

    expect(screen.queryByTestId("toggle")).not.toBeInTheDocument();
    expect(screen.getByTestId("text-area")).toBeInTheDocument();

    userEvent.click(typeSelect);
    userEvent.click(screen.getByText("Select"));

    expect(screen.queryByTestId("text-area")).not.toBeInTheDocument();
    expect(screen.getByTestId("select")).toBeInTheDocument();
  });

  it("Shouldn't save parameter without key, label and type defined", async () => {
    global.rtlContextRouterRender(<Inputs {...props} isEdit={false} property={undefined} />);

    const nameInput = screen.getByLabelText("Name");
    const labelInput = screen.getByLabelText("Label");
    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    userEvent.type(nameInput, "test");
    userEvent.type(labelInput, "test");

    userEvent.click(typeSelect);
    userEvent.click(screen.getByText("Boolean"));

    const createButton = await screen.findByRole("button", { name: /create/i });
    expect(createButton).toBeEnabled();
  });
});
