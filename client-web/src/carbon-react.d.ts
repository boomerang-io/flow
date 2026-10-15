// Augments (not replaces) @carbon/react's typings where they fall short of what the component
// renders. Kept apart from react-app-env.d.ts because an augmentation only works from a module
// file - in that global script file the `declare module` below would shadow the real module.
import type { InputHTMLAttributes } from "react";

declare module "@carbon/react/lib/components/DatePickerInput/DatePickerInput" {
  // DatePickerInputProps extends HTMLAttributes<HTMLInputElement>, which has no `autoComplete`,
  // yet DatePickerInput spreads its remaining props onto the <input>. "off" keeps the browser's
  // autofill list from covering the calendar.
  interface DatePickerInputProps {
    autoComplete?: InputHTMLAttributes<HTMLInputElement>["autoComplete"];
  }
}
