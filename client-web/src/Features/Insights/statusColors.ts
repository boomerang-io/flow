import { useEffect, useState } from "react";

export type OutcomeKey = "succeeded" | "failed" | "timedOut" | "cancelled" | "other";

export const outcomeLabel: Record<OutcomeKey, string> = {
  succeeded: "Succeeded",
  failed: "Failed",
  timedOut: "Timed out",
  cancelled: "Cancelled",
  other: "Other",
};

// The chart library takes literal colours, so the status tokens are read off the document at
// mount. The fallbacks are the Boomerang theme's values for the same tokens (support-success,
// support-error, support-warning, text-placeholder) and the Flow purple accent; they only paint
// on the server render and until the effect runs.
const tokens: Record<OutcomeKey, { variable: string; fallback: string }> = {
  succeeded: { variable: "--cds-support-success", fallback: "#009d9a" },
  failed: { variable: "--cds-support-error", fallback: "#da1e28" },
  timedOut: { variable: "--cds-support-warning", fallback: "#f1c21b" },
  cancelled: { variable: "--cds-text-placeholder", fallback: "#878d96" },
  other: { variable: "--flow-switch-primary", fallback: "#6e32c9" },
};

export type StatusColors = Record<OutcomeKey, string>;

const fallbackColors: StatusColors = Object.fromEntries(
  Object.entries(tokens).map(([key, { fallback }]) => [key, fallback]),
) as StatusColors;

export function useStatusColors(): StatusColors {
  const [colors, setColors] = useState<StatusColors>(fallbackColors);
  useEffect(() => {
    const style = getComputedStyle(document.documentElement);
    const resolved = Object.fromEntries(
      Object.entries(tokens).map(([key, { variable, fallback }]) => [key, style.getPropertyValue(variable).trim() || fallback]),
    ) as StatusColors;
    setColors(resolved);
  }, []);
  return colors;
}
