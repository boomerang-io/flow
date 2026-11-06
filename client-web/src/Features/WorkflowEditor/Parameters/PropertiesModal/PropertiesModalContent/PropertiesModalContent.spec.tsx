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

    userEvent.selectOptions(typeSelect, "Boolean");

    expect(screen.queryByTestId("text-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("toggle")).toBeInTheDocument();

    userEvent.selectOptions(typeSelect, "Text Area");

    expect(screen.queryByTestId("toggle")).not.toBeInTheDocument();
    expect(screen.getByTestId("text-area")).toBeInTheDocument();

    userEvent.selectOptions(typeSelect, "Select");

    expect(screen.queryByTestId("text-area")).not.toBeInTheDocument();
    expect(screen.getByTestId("select")).toBeInTheDocument();
  });

  // Carbon's Dropdown smooth-scrolled every option the pointer crossed into view; inside the modal's
  // scrolling body that moved the list under the pointer, and choosing with the mouse never settled.
  it("chooses a type with the mouse without scrolling anything into view", () => {
    const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
    renderWithContext(<Inputs {...props} isEdit={false} property={undefined} />);
    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    userEvent.click(typeSelect);
    userEvent.hover(screen.getByRole("option", { name: "Number" }));
    userEvent.selectOptions(typeSelect, screen.getByRole("option", { name: "Boolean" }));

    expect(typeSelect).toHaveValue("boolean");
    expect(screen.getByTestId("toggle")).toBeInTheDocument();
    expect(scrollIntoView).not.toHaveBeenCalled();
    scrollIntoView.mockRestore();
  });

  it("enables Add once a name and type are given; the label is optional", async () => {
    renderWithContext(<Inputs {...props} isEdit={false} property={undefined} />);

    const nameInput = screen.getByLabelText("Name");
    const typeSelect = screen.getByRole("combobox", { name: /type/i });

    userEvent.type(nameInput, "test");

    userEvent.selectOptions(typeSelect, "Boolean");

    const createButton = await screen.findByRole("button", { name: "Add" });
    expect(createButton).toBeEnabled();
  });
});
