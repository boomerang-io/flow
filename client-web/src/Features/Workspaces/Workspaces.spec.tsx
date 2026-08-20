import React from "react";
import Workspaces, { action, loader } from "Features/Workspaces/Workspaces";
import { Route } from "react-router-dom";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { startApiServer } from "ApiServer";

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

// Route-module test pattern (see GlobalParameters.spec.tsx): attach loader/action to the <Route>
// the same way AppRoutes.tsx does via app/routes/workspaceList.tsx, so rtlContextRouterRender
// actually exercises them instead of leaving useLoaderData() undefined.
function renderWorkspaces() {
  return global.rtlContextRouterRender(
    <Route path={AppPath.WorkspaceList} loader={loader} action={action} element={<Workspaces />} />,
    { route: appLink.workspaceList() },
  );
}

describe("Workspaces --- Snapshot Test", () => {
  it("Capturing Snapshot of Workspaces", async () => {
    const { baseElement } = renderWorkspaces();
    await screen.findByText("Tyson Workspace");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Workspaces --- RTL", () => {
  test("Create new workspace", async () => {
    renderWorkspaces();
    const createWorkspaceButton = await screen.findByText(/^Create Workspace$/i);
    fireEvent.click(createWorkspaceButton);
    expect(screen.getByText(/^Scope your workflows and parameters to a workspace$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Create$/i)).toBeDisabled();
    const workspaceNameInput = screen.getByLabelText(/^Display Name$/i);
    userEvent.type(workspaceNameInput, "Test workspace");
    expect(screen.getByText(/^Create$/i)).toBeEnabled();
    fireEvent.click(screen.getByText(/^Create$/i));
    expect(await screen.findByText(/Test workspace/i)).toBeInTheDocument();
  });
});
