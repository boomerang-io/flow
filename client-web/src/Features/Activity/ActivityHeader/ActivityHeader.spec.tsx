import React from "react";
import { renderWithRouter } from "Utils/testing/render";
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
    // ActivityHeader renders a react-router-dom <Link> (the "Home" breadcrumb) - a bare RTL
    // render has no Router context, which throws ("Cannot destructure property 'basename' of
    // 'React.useContext(...)' as it is null") the moment <Link> tries to read it.
    // renderWithRouter wraps it in a real (stubbed) data router.
    const { baseElement } = renderWithRouter(<ActivityHeader {...props} />);
    expect(baseElement).toMatchSnapshot();
  });
});
