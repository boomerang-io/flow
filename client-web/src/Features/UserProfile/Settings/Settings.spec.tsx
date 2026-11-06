import { Route } from "react-router-dom";
import { fireEvent, screen, within } from "@testing-library/react";
import { profile as userFixture } from "ApiServer/fixtures";
import type { FlowUser } from "Types";
import { renderWithContext } from "Utils/testing/render";
import Settings from "./Settings";

const user = userFixture as unknown as FlowUser;

function renderSettings(workspaces: FlowUser["workspaces"] = user.workspaces) {
  return renderWithContext(
    <Route path="/profile" element={<Settings user={{ ...user, workspaces }} userManagementEnabled />} />,
    { route: "/profile" },
  );
}

describe("UserProfile Settings", () => {
  test("the display-name dialog is about the user's display name, not a workspace", async () => {
    renderSettings();
    fireEvent.click(await screen.findByTestId("open-change-name-modal"));
    expect(screen.getByText("Change your display name")).toBeInTheDocument();
    expect(screen.queryByText("Change workspace name")).not.toBeInTheDocument();
  });

  test("Delete Account carries its own test id", async () => {
    renderSettings();
    expect(await screen.findByTestId("delete-account")).toHaveTextContent("Delete Account");
    expect(screen.queryByTestId("close-workspace")).not.toBeInTheDocument();
  });
});

describe("UserProfile Settings --- Your workspaces", () => {
  test("lists each workspace by its display name, with your role and a link to it", async () => {
    renderSettings();

    const list = await screen.findByLabelText("Your workspaces");
    const rows = within(list).getAllByRole("row").slice(1);
    expect(rows.map((row) => row.textContent)).toEqual([
      "admin-flowabl-io Personal WorkspaceEditor",
      "System and AdministrationReader",
      "Tyson WorkspaceOwner",
    ]);
    expect(within(list).getByRole("link", { name: "Tyson Workspace" })).toHaveAttribute("href", "/tyson-workspace/workflows");
  });

  test("says so when you belong to no workspace", async () => {
    renderSettings([]);

    expect(await screen.findByText("No workspaces yet")).toBeInTheDocument();
    expect(screen.queryByLabelText("Your workspaces")).not.toBeInTheDocument();
  });
});
