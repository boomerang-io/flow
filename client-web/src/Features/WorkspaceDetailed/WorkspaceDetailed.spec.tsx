import React from "react";
import WorkspaceDetailed from ".";
import { Route, useParams } from "react-router-dom";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { db } from "ApiServer/msw/db";
import { WorkspaceContextProvider } from "State/context";
import { useQuery } from "Hooks";
import { serviceUrl } from "Config/servicesConfig";
import { FlowWorkspace } from "Types";

// Mirrors App.tsx's WorkspaceContainer: resolves the active workspace from the `:workspace`
// route param and re-fetches (and re-provides fresh context) whenever navigation changes it -
// e.g. after a rename that pushes to a new `:workspace` slug.
function WorkspaceContainer({ children }: { children: React.ReactNode }) {
  const { workspace = "" } = useParams<{ workspace: string }>();
  const workspaceQuery = useQuery<FlowWorkspace>(serviceUrl.resourceWorkspace({ workspace }));

  if (!workspaceQuery.data) return null;

  return <WorkspaceContextProvider value={{ workspace: workspaceQuery.data }}>{children}</WorkspaceContextProvider>;
}

// `workspace.js` is a standalone fixture, not one of the records in `workspaces.js`'s list (the
// one `ApiServer/msw/db`'s `workspaces` collection seeds from) - Mirage's `resourceWorkspace` GET
// handler ignored its `:workspace` param and always served this fixture regardless of what was
// asked for, which is what let this spec navigate to a workspace nothing actually seeded. MSW's
// handler does a real lookup by name/id (see handlers.ts's `findWorkspace`), so seed this
// fixture into the store directly - the same real lookup then finds it for every call this spec
// makes (GET, and the PATCH the rename flow below depends on actually persisting).
beforeEach(() => {
  db.workspaces.push(structuredClone(workspaceFixture));
});

describe("WorkspaceDetailed --- Snapshot Test", () => {
  it("Capturing Snapshot of WorkspaceDetailed", async () => {
    const { baseElement } = rtlContextRouterRender(
      <Route
        path={`${AppPath.ManageWorkspace}/*`}
        element={
          <WorkspaceContainer>
            <WorkspaceDetailed />
          </WorkspaceContainer>
        }
      />,
      { route: appLink.manageWorkspace({ workspace: workspaceFixture.name }) }
    );
    await screen.findByText("These are the people who have access to this Workspace.");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkspaceDetailed --- RTL", () => {
  test("Visit Workspace Details tabs", async () => {
    rtlContextRouterRender(
      <Route
        path={`${AppPath.ManageWorkspace}/*`}
        element={
          <WorkspaceContainer>
            <WorkspaceDetailed />
          </WorkspaceContainer>
        }
      />,
      { route: appLink.manageWorkspace({ workspace: workspaceFixture.name }) }
    );
    //Members tab
    await screen.findByText("These are the people who have access to this Workspace.");
    const addMemberButton = await screen.findByText(/^Add Existing Members$/i);
    fireEvent.click(addMemberButton);
    expect(screen.getByText(/^Search for existing members to add to this workspace$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Add to workspace$/i)).toBeDisabled();

    expect(screen.getByPlaceholderText(/^Search for a user$/i)).toBeInTheDocument();
    // fetch users is triggering a promise error, need to look for a solution
    // const nameInput = screen.getByPlaceholderText(/^Search for a user$/i);
    // userEvent.type(nameInput, "e");
    // fireEvent.click(await screen.findByText(/^Test User$/i));
    // expect(await screen.findByText(/^Add to workspace$/i)).toBeEnabled();
    fireEvent.click(screen.getByText(/^Cancel$/i));

    //Workflows
    fireEvent.click(screen.getByText("Workflows"));
    expect(screen.getByText("These are the workflows for this Workspace.")).toBeInTheDocument();

    //Settings
    fireEvent.click(screen.getByText("Settings"));
    expect(await screen.findByText("Basic details")).toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("open-change-name-modal"));
    expect(screen.getByText("Change workspace name")).toBeInTheDocument();
    userEvent.type(screen.getByLabelText("Display Name"), " test name");
    fireEvent.click(screen.getByText("Save"));
    // Appears twice: the breadcrumb and the "Display Name" field in Settings (the header no
    // longer also shows a standalone workspace-name title, unlike when this assertion was written).
    expect(await (await screen.findAllByText("IBM Services Engineering test name")).length).toBe(2);
  });
});
