import React from "react";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { AppPath, appLink } from "Config/appConfig";
import { ChangeLog as ChangeLogType } from "Types";
import ChangeLog from ".";

const changeLogData: ChangeLogType = [
  { version: 2, author: "Jenny", reason: "Update task to undo Bob's work", date: "2023-08-17T22:34:05.234+00:00" },
  { version: 1, author: "Bob", reason: "Add new task", date: "2023-08-16T22:34:05.234+00:00" },
];

const props = { changeLogData };

describe("ChangeLog --- Snapshot Test", () => {
  it("Capturing Snapshot of ChangeLog", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <Route path={AppPath.EditorChangelog} element={<ChangeLog {...props} />} />,
      { route: appLink.editorChangelog({ workspace: "tyson-workspace", workflow: "test-workflow" }) },
    );
    await screen.findByText("Add new task");
    expect(baseElement).toMatchSnapshot();
  });
});
