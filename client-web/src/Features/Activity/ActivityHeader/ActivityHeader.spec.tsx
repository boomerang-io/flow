import React from "react";
import ActivityHeader from "./index";

const props = {
  failedActivities: 10,
  runActivities: 25,
  succeededActivities: 13,
  inProgressActivities: 0,
  isLoading: false,
  isError: false,
  workspace: { displayName: "Test Workspace" },
};

describe("ActivityHeader --- Snapshot", () => {
  it("Capturing Snapshot of ActivityHeader", () => {
    const { baseElement } = global.rtlRender(<ActivityHeader {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
