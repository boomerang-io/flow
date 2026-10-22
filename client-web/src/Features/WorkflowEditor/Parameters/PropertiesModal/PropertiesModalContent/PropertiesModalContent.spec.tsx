import { vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { DataDrivenInput } from "Types";
import { renderWithContext } from "Utils/testing/render";
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
    const { baseElement } = renderWithContext(<Inputs {...props} />);

    expect(baseElement).toMatchSnapshot();
  });
});

describe("Inputs --- RTL", () => {
  it("Change default value by type correctly", async () => {
    renderWithContext(<Inputs {...props} />);
    expect(screen.getByTestId("text-input")).toBeInTheDocument();

    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    // Type is a Carbon Dropdown: open it with a click and pick the option.
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

  it("enables Add once a name and type are given; the label is optional", async () => {
    renderWithContext(<Inputs {...props} isEdit={false} property={undefined} />);

    const nameInput = screen.getByLabelText("Name");
    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    userEvent.type(nameInput, "test");

    userEvent.click(typeSelect);
    userEvent.click(screen.getByText("Boolean"));

    const createButton = await screen.findByRole("button", { name: "Add" });
    expect(createButton).toBeEnabled();
  });
});
