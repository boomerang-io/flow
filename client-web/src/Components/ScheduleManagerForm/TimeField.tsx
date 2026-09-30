import React from "react";
import { SelectItem, TimePicker, TimePickerSelect } from "@carbon/react";
import moment from "moment-timezone";

interface TimeFieldProps {
  id: string;
  invalid?: boolean;
  invalidText?: string;
  labelText: string;
  onBlur?: () => void;
  // "HH:mm" in 24-hour time, or "" while the typed time is incomplete.
  onChange: (value: string) => void;
  value: string;
}

const TWELVE_HOUR = /^(1[0-2]|0?[1-9]):([0-5]\d)$/;

function toParts(value: string): { text: string; period: "AM" | "PM" } {
  const time = moment(value, "HH:mm", true);
  return time.isValid() ? { text: time.format("h:mm"), period: time.format("A") as "AM" | "PM" } : { text: "", period: "AM" };
}

function toValue(text: string, period: string): string {
  return TWELVE_HOUR.test(text.trim()) ? moment(`${text.trim()} ${period}`, "h:mm A").format("HH:mm") : "";
}

/** Carbon's time picker with an AM/PM select, holding the time as 24-hour "HH:mm". */
export default function TimeField(props: TimeFieldProps) {
  const [text, setText] = React.useState(() => toParts(props.value).text);
  const [period, setPeriod] = React.useState(() => toParts(props.value).period);

  // Follow a value set from outside (a reset, or another field filling it in).
  React.useEffect(() => {
    if (props.value && props.value !== toValue(text, period)) {
      const parts = toParts(props.value);
      setText(parts.text);
      setPeriod(parts.period);
    }
  }, [props.value]);

  return (
    <TimePicker
      id={props.id}
      invalid={props.invalid}
      invalidText={props.invalidText}
      labelText={props.labelText}
      onBlur={props.onBlur}
      onChange={(event: React.ChangeEvent<HTMLInputElement>) => {
        setText(event.target.value);
        props.onChange(toValue(event.target.value, period));
      }}
      placeholder="hh:mm"
      value={text}
    >
      <TimePickerSelect
        id={`${props.id}-period`}
        aria-label="AM or PM"
        value={period}
        onChange={(event: React.ChangeEvent<HTMLSelectElement>) => {
          const next = event.target.value as "AM" | "PM";
          setPeriod(next);
          props.onChange(toValue(text, next));
        }}
      >
        <SelectItem value="AM" text="AM" />
        <SelectItem value="PM" text="PM" />
      </TimePickerSelect>
    </TimePicker>
  );
}
