import { screen, within } from "@testing-library/react";
import { profile } from "ApiServer/fixtures";
import { FlowUser } from "Types";
import { renderWithContext } from "Utils/testing/render";
import Settings from "./Settings";

const user = profile as unknown as FlowUser;

function renderSettings(workspaces: FlowUser["workspaces"]) {
  return renderWithContext(<Settings user={{ ...user, workspaces }} userManagementEnabled />);
}

describe("Profile --- Your workspaces", () => {
  test("lists each workspace by its display name, with your role and a link to it", async () => {
    renderSettings(user.workspaces);

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
