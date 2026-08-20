import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { action, loader } from "./Workflows";
import WorkflowsHome from "./index";
import { workspaces, workspace as workspaceFixture, profile } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { WorkspaceContextProvider } from "State/context";

// Route-module test pattern (see GlobalParameters.spec.tsx): build the same shape the real router
// config uses (app/routes/workflows.tsx - a route carrying loader/action alongside its element,
// matched on the real "/:workspace/workflows" path so the loader/action's `params.workspace` read
// resolves) and hand it to rtlContextRouterRender - the helper detects a <Route> element and uses
// it as-is instead of wrapping it in its usual catch-all, so the loader/action actually run.
describe("WorkflowsHome --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHome", async () => {
    const { baseElement } = rtlContextRouterRender(
      <Route
        path={AppPath.Workflows}
        loader={loader}
        action={action}
        element={
          <WorkspaceContextProvider value={{ workspace: workspaceFixture }}>
            <WorkflowsHome />
          </WorkspaceContextProvider>
        }
      />,
      {
        contextValue: {
          isTutorialActive: false,
          communityUrl: "www.ibm.com",
          setIsTutorialActive: () => {},
          user: profile,
          workspaces,
        },
        route: appLink.workflows({ workspace: workspaceFixture.name }),
      }
    );
    // Wait for the workflow list to finish loading (the loader resolves before this component
    // renders, but rtlContextRouterRender's own router still needs a tick to settle it) - the
    // count only appears in the title once the workflows have loaded.
    await screen.findByText("Workflows (3)");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowsHome --- loader", () => {
  test("resolves the workspace's workflows", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspaceFixture.name })}`);

    const result = await loader({ request, params: { workspace: workspaceFixture.name } });

    expect(result.errorLoading).toBe(false);
    expect(result.workflows.length).toBe(3);
  });
});
