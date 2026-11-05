import React from "react";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { ChangeLog as ChangeLogType } from "Types";
import { renderWithContext } from "Utils/testing/render";
import ChangeLog from ".";

const changeLogData: ChangeLogType = [
  { version: 2, author: "Jenny", reason: "Update task to undo Bob's work", date: "2023-08-17T22:34:05.234+00:00" },
  { version: 1, author: "Bob", reason: "Add new task", date: "2023-08-16T22:34:05.234+00:00" },
];

const props = { changeLogData };

function renderChangeLog() {
  return renderWithContext(<Route path={AppPath.EditorChangelog} element={<ChangeLog {...props} />} />, {
    route: appLink.editorChangelog({ workspace: "tyson-workspace", workflow: "test-workflow" }),
  });
}

describe("ChangeLog --- Snapshot Test", () => {
  it("Capturing Snapshot of ChangeLog", async () => {
    const { baseElement } = renderChangeLog();
    await screen.findByText("Add new task");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("ChangeLog --- RTL", () => {
  // The API names who made each version `author` (a user's name, resolved on read).
  it("shows each version's author in the User column and searches by it", async () => {
    renderChangeLog();
    expect(await screen.findByText("Jenny")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();

    userEvent.type(screen.getByRole("searchbox"), "Jenny");

    expect(await screen.findByText("Update task to undo Bob's work")).toBeInTheDocument();
    expect(screen.queryByText("Add new task")).not.toBeInTheDocument();
  });
});
