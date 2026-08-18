import React from "react";
import Workspaces from ".";
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

describe("Workspaces --- Snapshot Test", () => {
  it("Capturing Snapshot of Workspaces", async () => {
    const { baseElement } = global.rtlContextRouterRender(
      <Route path={AppPath.WorkspaceList}>
        <Workspaces />
      </Route>,
      { route: appLink.workspaceList() }
    );
    await screen.findByText("Tyson Workspace");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Workspaces --- RTL", () => {
  test("Create new workspace", async () => {
    global.rtlContextRouterRender(
      <Route path={AppPath.WorkspaceList}>
        <Workspaces />
      </Route>,
      { route: appLink.workspaceList() }
    );
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
