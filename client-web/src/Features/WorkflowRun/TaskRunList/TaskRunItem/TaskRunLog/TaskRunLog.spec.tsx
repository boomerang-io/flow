import React from "react";
import TaskExecutionLog from "./index";

const props = {
  taskrunId: "2",
  taskName: "Send Slack Message",
};

describe("TaskExecutionLog --- Snapshot", () => {
  it("Capturing Snapshot of TaskExecutionLog", () => {
    const { baseElement } = global.rtlRender(<TaskExecutionLog {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
