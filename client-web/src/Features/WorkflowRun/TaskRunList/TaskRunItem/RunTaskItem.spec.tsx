import React from "react";
import TaskItem from "./index";

const props = {
  flowActivityId: "1",
  task: {
    activityId: "5c36289096052900012cc81d",
    duration: 300190,
    flowTaskStatus: "completed",
    id: "5c36289096052900012cc81e",
    order: 1,
    startTime: "2019-09-03T15:00:00.230+0000",
    taskId: "515c8b05-ceb0-470a-a58e-b8740b332a6a",
    outputs: {
      args: {
        test: "true",
      },
    },
    taskName: "Send Slack Message",
  },
  hidden: false,
};

describe("TaskItem --- Snapshot", () => {
  it("Capturing Snapshot of TaskItem", () => {
    const { baseElement } = global.rtlRender(<TaskItem {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
