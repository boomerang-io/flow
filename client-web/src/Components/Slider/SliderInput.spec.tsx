import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { Formik } from "formik";
import { vi } from "vitest";
import { normaliseInputs } from "Utils/paramsHelper";
import { InputType } from "Constants";
import SliderInput from "./SliderInput";
import { snapToStep } from "./Slider";

// The bounds the seeded `ai` task's `temperature` parameter carries.
const temperatureParam = {
  name: "temperature",
  label: "Temperature",
  helperText: "Lower is more deterministic",
  type: InputType.Slider,
  min: 0,
  max: 2,
  step: 0.1,
  default: "0.7",
};

function renderSliderInput(overrides: Record<string, unknown> = {}) {
  const setFieldValue = vi.fn();
  const formikProps = { setFieldValue, setFieldTouched: vi.fn() } as never;
  render(
    <Formik initialValues={{}} onSubmit={() => {}}>
      <SliderInput
        formikProps={formikProps}
        id="['temperature']"
        name="['temperature']"
        label={temperatureParam.label}
        helperText={temperatureParam.helperText}
        min={temperatureParam.min}
        max={temperatureParam.max}
        step={temperatureParam.step}
        value={temperatureParam.default}
        {...overrides}
      />
    </Formik>,
  );
  return { setFieldValue };
}

describe("SliderInput", () => {
  it("renders the parameter's min, max and step on both the slider and its number input", () => {
    renderSliderInput();

    const numberInput = screen.getByRole("spinbutton");
    expect(numberInput).toHaveAttribute("min", "0");
    expect(numberInput).toHaveAttribute("max", "2");
    expect(numberInput).toHaveAttribute("step", "0.1");
    // The Carbon slider exposes the same bounds through its ARIA contract.
    const slider = screen.getByRole("slider");
    expect(slider).toHaveAttribute("aria-valuemin", "0");
    expect(slider).toHaveAttribute("aria-valuemax", "2");
  });

  it("shows the default value the catalogue supplies", () => {
    renderSliderInput();
    expect(screen.getByRole("spinbutton")).toHaveValue(0.7);
  });

  it("writes the value back to formik as a string, under the input's bracketed field path", () => {
    const { setFieldValue } = renderSliderInput();

    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "1.2" } });

    expect(setFieldValue).toHaveBeenCalledWith("['temperature']", "1.2");
    // A string, never a number - task params are strings all the way to the engine.
    expect(typeof setFieldValue.mock.calls[0][1]).toBe("string");
  });

  it("clamps a value past the configured maximum", () => {
    const { setFieldValue } = renderSliderInput();

    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "9" } });

    expect(setFieldValue).toHaveBeenCalledWith("['temperature']", "2");
  });

  it("snaps to the step rather than leaving floating point noise", () => {
    // 0.1 * 7 is 0.7000000000000001 without the rounding in snapToStep.
    expect(snapToStep(0.68, 0, 0.1)).toBe(0.7);
    expect(snapToStep(1.93, 0, 0.1)).toBe(1.9);
    expect(snapToStep(7, 0, 1)).toBe(7);
  });
});

describe("normaliseInputs --- slider registration", () => {
  it("routes a slider param to the Slider custom component", () => {
    const [input] = normaliseInputs([temperatureParam]);
    expect(input.customComponent).toBe(SliderInput);
    expect(input.key).toBe("temperature");
    expect(input.defaultValue).toBe("0.7");
  });

  it("leaves every other param type alone", () => {
    const [input] = normaliseInputs([{ name: "model", type: InputType.Text }]);
    expect(input.customComponent).toBeUndefined();
  });
});
