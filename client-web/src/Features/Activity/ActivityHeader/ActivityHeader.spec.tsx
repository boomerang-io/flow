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
    // ActivityHeader renders a react-router-dom <Link> (the "Home" breadcrumb) - global.rtlRender
    // is a bare RTL render with no Router context, which throws
    // ("Cannot destructure property 'basename' of 'React.useContext(...)' as it is null") the
    // moment <Link> tries to read it. global.rtlRouterRender wraps in a real RouterProvider.
    const { baseElement } = global.rtlRouterRender(<ActivityHeader {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
