import React from "react";
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
import WorkspaceDetailed, { loader } from "./WorkspaceDetailed";
import ApproverGroups from "./ApproverGroups";
import Members from "./Members";
import Quotas from "./Quotas";
import Settings from "./Settings";
import Tokens from "./Tokens";
import Workflows from "./Workflows";

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

// The Manage Workspace tabs are real nested routes now (see app/routes.ts), so the spec builds
// the same tree: a layout route carrying the loader that fetches the workspace record, with one
// child route per tab. Members stays the index route at the bare `/:workspace/manage` path.
function renderWorkspaceDetailed(route: string = appLink.manageWorkspace({ workspace: workspaceFixture.name })) {
  return rtlContextRouterRender(
    <Route
      path={AppPath.ManageWorkspace}
      loader={loader}
      element={
        <WorkspaceContainer>
          <WorkspaceDetailed />
        </WorkspaceContainer>
      }
    >
      <Route index element={<Members />} />
      <Route path="workflows" element={<Workflows />} />
      <Route path="approver-groups" element={<ApproverGroups />} />
      <Route path="quotas" element={<Quotas />} />
      <Route path="tokens" element={<Tokens />} />
      <Route path="settings" element={<Settings />} />
    </Route>,
    { route },
  );
}

describe("WorkspaceDetailed --- Snapshot Test", () => {
  it("Capturing Snapshot of WorkspaceDetailed", async () => {
    const { baseElement } = renderWorkspaceDetailed();
    await screen.findByText("These are the people who have access to this Workspace.");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkspaceDetailed --- nested tab routes", () => {
  test("deep-links straight into a tab rather than always landing on Members", async () => {
    renderWorkspaceDetailed(appLink.manageWorkspaceQuotas({ workspace: workspaceFixture.name }));
    expect(
      await screen.findByText(
        "The following quotas have been set for the workspace - only administrators have access to adjust these.",
      ),
    ).toBeInTheDocument();
    // The Members tab's own body must NOT be mounted - each tab is its own route now.
    expect(screen.queryByText("These are the people who have access to this Workspace.")).not.toBeInTheDocument();
  });

  test("deep-links into the approver groups tab", async () => {
    renderWorkspaceDetailed(appLink.manageWorkspaceApprovers({ workspace: workspaceFixture.name }));
    expect(
      await screen.findByText(
        "Create groups of users to be able to set the entire group as an approver in an Action.",
      ),
    ).toBeInTheDocument();
  });

  test("deep-links into the tokens tab", async () => {
    renderWorkspaceDetailed(appLink.manageWorkspaceTokens({ workspace: workspaceFixture.name }));
    expect(await screen.findByTestId("create-token-button")).toBeInTheDocument();
  });
});

describe("WorkspaceDetailed --- RTL", () => {
  test("Visit Workspace Details tabs", async () => {
    renderWorkspaceDetailed();
    //Members tab
    await screen.findByText("These are the people who have access to this Workspace.");
    const addMemberButton = await screen.findByText(/^Add Existing Members$/i);
    fireEvent.click(addMemberButton);
    expect(screen.getByText(/^Search for existing members to add to this workspace$/i)).toBeInTheDocument();
    expect(screen.getByText(/^Add to workspace$/i)).toBeDisabled();

    expect(screen.getByPlaceholderText(/^Search for a user$/i)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/^Cancel$/i));

    //Workflows
    fireEvent.click(screen.getByText("Workflows"));
    expect(await screen.findByText("These are the workflows for this Workspace.")).toBeInTheDocument();

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
