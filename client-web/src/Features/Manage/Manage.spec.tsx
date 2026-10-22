import { Outlet, Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { renderWithContext } from "Utils/testing/render";
import type { RoutePermissions } from "Features/App/AppRoutes";
import Manage, { ManageIndex } from "./Manage";

const everything: RoutePermissions = {
  canReadSettings: true,
  canReadParameters: true,
  canReadTasks: true,
  canReadTokens: true,
  canReadWorkspaces: true,
  canReadUsers: true,
  canReadAudit: true,
  activityEnabled: true,
  insightsEnabled: true,
  workspaceParametersEnabled: true,
};

// The layout reads the route permissions the App layout hands down through its outlet
// context, so the stub tree puts a parent route above it that supplies them.
function renderManage(permissions: RoutePermissions, route = "/admin/settings", tokensEnabled = true) {
  return renderWithContext(
    <Route path="/" element={<Outlet context={permissions} />}>
      <Route path="/admin" element={<Manage />}>
        <Route index element={<ManageIndex />} />
        <Route path="settings" element={<p>settings content</p>} />
        <Route path="audit" element={<p>audit content</p>} />
      </Route>
    </Route>,
    { route, features: { TokensEnabled: tokensEnabled } },
  );
}

describe("Manage", () => {
  test("shows one tab per area the caller may read, in order", async () => {
    renderManage(everything);

    expect(await screen.findByRole("heading", { name: "Manage" })).toBeInTheDocument();
    const tabs = screen.getAllByRole("link", { name: /Settings|Workspaces|Users|Parameters|Tokens|Tasks|Audit/ });
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      "Settings",
      "Workspaces",
      "Users",
      "Parameters",
      "Tokens",
      "Tasks",
      "Audit",
    ]);
    expect(screen.getByText("settings content")).toBeInTheDocument();
  });

  test("hides the tabs the caller's grants do not cover", async () => {
    renderManage({ ...everything, canReadSettings: false, canReadUsers: false, canReadAudit: false }, "/admin/audit");

    await screen.findByRole("heading", { name: "Manage" });
    expect(screen.queryByRole("link", { name: "Settings" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Audit" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Tokens" })).toBeInTheDocument();
  });

  test("the area's root sends the caller to the first tab they may see", async () => {
    renderManage({ ...everything, canReadSettings: false, canReadWorkspaces: false, canReadUsers: false, canReadParameters: false, canReadTokens: false, canReadTasks: false }, "/admin");

    expect(await screen.findByText("audit content")).toBeInTheDocument();
  });

  test("hides Tokens when security is off, whatever the caller may read", async () => {
    renderManage(everything, "/admin/settings", false);

    expect(await screen.findByRole("heading", { name: "Manage" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Tokens" })).not.toBeInTheDocument();
  });
});
