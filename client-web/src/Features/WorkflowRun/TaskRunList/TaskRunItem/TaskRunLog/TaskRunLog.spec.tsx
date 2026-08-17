import React from "react";
import TaskExecutionLog from "./index";

const props = {
  flowActivityId: "1",
  flowTaskId: "2",
  flowTaskName: "Send Slack Message",
};

describe("TaskExecutionLog --- Snapshot", () => {
  it("Capturing Snapshot of TaskExecutionLog", () => {
    const { baseElement } = global.rtlRender(<TaskExecutionLog {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
