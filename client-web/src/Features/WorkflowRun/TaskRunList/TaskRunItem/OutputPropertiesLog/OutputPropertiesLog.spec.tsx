import React from "react";
import OutputPropertiesLog from "./index";

const props = {
  taskName: "Send Slack Message",
  results: [{ name: "args", description: "", value: "test" }],
};

describe("OutputPropertiesLog --- Snapshot", () => {
  it("Capturing Snapshot of OutputPropertiesLog", () => {
    const { baseElement } = global.rtlRender(<OutputPropertiesLog {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
