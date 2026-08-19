import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import WorkflowsHome from "./index";
import { startApiServer } from "ApiServer";
import { workspaces, workspace as workspaceFixture, profile } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { AppContextProvider, WorkspaceContextProvider } from "State/context";

let server;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("WorkflowsHome --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHome", async () => {
    const { baseElement } = rtlContextRouterRender(
      <AppContextProvider
        value={{
          isTutorialActive: false,
          communityUrl: "www.ibm.com",
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        }}
      >
        <WorkspaceContextProvider value={{ workspace: workspaceFixture }}>
          <Route path={AppPath.Workflows}>
            <WorkflowsHome />
          </Route>
        </WorkspaceContextProvider>
      </AppContextProvider>,
      { route: appLink.workflows({ workspace: workspaceFixture.name }) }
    );
    // Wait for the workflow list to finish loading (the header subtitle renders during the
    // loading skeleton too, so asserting on it alone would snapshot a non-deterministic state).
    // The count only appears in the title once the workflows have loaded.
    await screen.findByText("Workflows (3)");
    expect(baseElement).toMatchSnapshot();
  });
});
