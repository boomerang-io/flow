import React, { Component } from "react";
import { Slider, TextInput } from "@carbon/react";
import omit from "lodash/omit";
import "./styles.scss";

export const valueTypes = {
  percentage: "%",
};

/**
 * Number of decimal places implied by a step, so a fractional step (0.1) keeps its precision
 * and an integer step (the default) still rounds to a whole number.
 */
export function stepDecimals(step: number): number {
  const decimals = String(step).split(".")[1];
  return decimals ? decimals.length : 0;
}

/**
 * Snap a value to the nearest multiple of `step` measured from `min`, then trim the floating
 * point noise multiplication leaves behind (0.1 * 7 === 0.7000000000000001).
 */
export function snapToStep(value: number, min: number, step: number): number {
  if (!step || step <= 0) return value;
  const snapped = min + Math.round((value - min) / step) * step;
  return Number(snapped.toFixed(stepDecimals(step)));
}

type Props = {
  id: string;
  min: number;
  max: number;
  onChange: (value: number) => void;
  "data-testid"?: string;
  disabled?: boolean;
  helperText?: string;
  inputType?: string;
  labelText?: string;
  readOnly?: boolean;
  sliderRef?: any;
  sliderValue?: number;
  step?: number;
};

type State = {
  value: number | string;
};

class BasicSlider extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      value: this.props.sliderValue ?? this.props.min ?? 0,
    };
  }

  propsHandler = () => {
    const newProps = {
      ...this.props,
      hideTextInput: true,
      // Bypass Slider 'number' validation for the input. This way the console warning will not be thrown.
      value: this.numericValue(),
      onChange: (e: { value: number }) => {
        const validatedValue = this.validateValue(e.value);
        this.setSliderValueAndPosition(validatedValue);
        this.props.onChange(validatedValue);
      },
    };

    // Remove custom props for Slider. `helperText`/`inputType` are ours, not Carbon's - left in
    // the spread they reached the DOM and React warned about an unrecognised attribute; the
    // helper text is rendered below instead.
    return omit({ ...newProps }, ["sliderRef", "sliderValue", "sliderType", "helperText", "inputType"]);
  };

  /** The state value parsed as a number, falling back to `min` while the text input is mid-edit. */
  numericValue = () => {
    const parsed = typeof this.state.value === "number" ? this.state.value : parseFloat(this.state.value);
    return Number.isNaN(parsed) ? (this.props.min ?? 0) : parsed;
  };

  validateValue = (value: number | string) => {
    const { min = 0, max = 100, step = 1 } = this.props;
    // Remove invalid characters - anything that isn't part of a (possibly negative, possibly
    // fractional) number. The old expression stripped "." and "-" too, which made every
    // fractional step unusable.
    const parsed = typeof value === "number" ? value : parseFloat(String(value).replace(/[^0-9.-]+/g, ""));
    if (Number.isNaN(parsed)) {
      return min;
    }
    // Value cannot fall outside the configured bounds
    if (parsed > max) return max;
    if (parsed < min) return min;
    return snapToStep(parsed, min, step);
  };

  setSliderValueAndPosition = (value: number) => {
    this.setState({ value: value });
  };

  /**
   * The text input stays free-form while typing (so a partial "0." isn't clobbered), but every
   * parseable keystroke is reported upward - it previously only moved local state, so a value
   * typed rather than dragged never reached the form.
   */
  handleSliderInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value;
    this.setState({ value: raw });
    const parsed = parseFloat(raw);
    if (!Number.isNaN(parsed)) {
      this.props.onChange(this.validateValue(parsed));
    }
  };

  handleSliderInputBlur = (e: React.FocusEvent<HTMLInputElement>) => {
    const validatedValue = this.validateValue(e.target.value);
    this.setSliderValueAndPosition(validatedValue);
    this.props.onChange(validatedValue);
  };

  render() {
    const { max, min, step = 1, helperText } = this.props;
    return (
      <div className="c-slider__wrapper">
        <div className="c-slider__container">
          <Slider {...this.propsHandler()} min={min} max={max} step={step} value={this.numericValue()} />
          <TextInput
            className="c-slider__slider-input"
            id={`${this.props.id}-text-input`}
            hideLabel
            labelText={this.props.labelText ?? ""}
            max={max}
            min={min}
            step={step}
            type="number"
            value={this.state.value}
            onBlur={this.handleSliderInputBlur}
            onChange={this.handleSliderInputChange}
          />
        </div>
        {helperText && <p className="c-slider__helper-text">{this.props.helperText}</p>}
        <div className="c-slider__input-divider" />
      </div>
    );
  }
}

export default BasicSlider;
