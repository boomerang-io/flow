import React from "react";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { vi } from "vitest";
import { renderWithContext } from "Utils/testing/render";
import { InputType } from "Constants";
import TemplateConfigModalContent from "./TemplateConfigModalContent";

function renderForm(field?: Record<string, unknown>) {
  const setFieldValue = vi.fn();
  renderWithContext(
    <TemplateConfigModalContent
      closeModal={() => {}}
      forceCloseModal={() => {}}
      field={field as never}
      fieldKeys={[]}
      isEdit={Boolean(field)}
      templateFields={[]}
      setFieldValue={setFieldValue}
    />,
  );
  return { setFieldValue };
}

/** Pick a type out of the "Type" combo box by its visible label. */
async function selectType(label: string) {
  fireEvent.click(screen.getByPlaceholderText("Select a type"));
  fireEvent.click(await screen.findByText(label));
}

describe("Task Manager parameter form --- the slider type", () => {
  it("offers Slider in the type list", async () => {
    renderForm();

    fireEvent.click(screen.getByPlaceholderText("Select a type"));

    expect(await screen.findByText("Slider")).toBeInTheDocument();
  });

  it("reveals min, max and step once Slider is chosen", async () => {
    renderForm();

    await selectType("Slider");

    expect(await screen.findByLabelText("Minimum")).toBeInTheDocument();
    expect(screen.getByLabelText("Maximum")).toBeInTheDocument();
    expect(screen.getByLabelText("Step")).toBeInTheDocument();
  });

  it("seeds the bounds the Slider component itself falls back to", async () => {
    renderForm();

    await selectType("Slider");

    await waitFor(() => expect(screen.getByLabelText("Minimum")).toHaveValue(0));
    expect(screen.getByLabelText("Maximum")).toHaveValue(100);
    expect(screen.getByLabelText("Step")).toHaveValue(1);
  });

  it("keeps the bounds off every other type", async () => {
    renderForm();

    await selectType("Text");

    expect(await screen.findByLabelText("Default Value (optional)")).toBeInTheDocument();
    expect(screen.queryByLabelText("Minimum")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Step")).not.toBeInTheDocument();
  });

  it("loads an existing slider parameter's bounds when editing", async () => {
    renderForm({
      name: "temperature",
      label: "Temperature",
      type: InputType.Slider,
      min: 0,
      max: 2,
      step: 0.1,
      default: "0.7",
    });

    expect(await screen.findByLabelText("Minimum")).toHaveValue(0);
    expect(screen.getByLabelText("Maximum")).toHaveValue(2);
    expect(screen.getByLabelText("Step")).toHaveValue(0.1);
  });

  it("saves the bounds as numbers on the parameter", async () => {
    const { setFieldValue } = renderForm({
      name: "temperature",
      label: "Temperature",
      type: InputType.Slider,
      min: 0,
      max: 2,
      step: 0.1,
      default: "0.7",
    });

    fireEvent.click(await screen.findByRole("button", { name: "Save" }));

    await waitFor(() => expect(setFieldValue).toHaveBeenCalled());
    const [, parameters] = setFieldValue.mock.calls[0];
    expect(parameters[0]).toMatchObject({ type: InputType.Slider, min: 0, max: 2, step: 0.1 });
  });
});
