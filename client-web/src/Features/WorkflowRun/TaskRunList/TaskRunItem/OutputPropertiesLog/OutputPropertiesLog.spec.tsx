import React from "react";
import { fireEvent, screen } from "@testing-library/react";
import OutputPropertiesLog from "./index";

// The modal body only renders once the trigger is clicked (ComposedModal invokes its `children`
// render prop on open), so a spec that only snapshots the closed modal captures the trigger
// button and nothing else - it passed with the component's body replaced by a `throw`. Every
// assertion below therefore opens the modal first.
//
// Descriptions are deliberately non-empty so that the one "---" in the table is unambiguously
// the missing-VALUE fallback (an empty description renders "---" too - see OutputPropertiesLog.tsx).
const props = {
  taskName: "Send Slack Message",
  results: [
    { name: "args", description: "The arguments passed to the task", value: "test" },
    { name: "unset", description: "A result the task never wrote", value: "" },
    { name: "payload", description: "An object-valued result", value: { channel: "#alerts", ok: true } },
  ],
};

function openModal() {
  global.rtlRender(<OutputPropertiesLog {...(props as any)} />);
  fireEvent.click(screen.getByText("View Parameters"));
}

describe("OutputPropertiesLog --- RTL", () => {
  it("renders every result name and its description once opened", () => {
    openModal();

    expect(screen.getByText("args")).toBeInTheDocument();
    expect(screen.getByText("unset")).toBeInTheDocument();
    expect(screen.getByText("payload")).toBeInTheDocument();
    expect(screen.getByText("The arguments passed to the task")).toBeInTheDocument();
  });

  it("renders a string value as-is", () => {
    openModal();

    expect(screen.getByText("test")).toBeInTheDocument();
  });

  it("falls back to --- for a result with no value", () => {
    openModal();

    // Exactly one: the three descriptions are all non-empty, so the only fallback in the table
    // is the `unset` result's value.
    expect(screen.getAllByText("---")).toHaveLength(1);
  });

  it("stringifies an object value rather than rendering [object Object]", () => {
    openModal();

    expect(screen.getByText(JSON.stringify({ channel: "#alerts", ok: true }))).toBeInTheDocument();
    expect(screen.queryByText("[object Object]")).not.toBeInTheDocument();
  });
});
