import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { action, loader } from "./Workflows";
import WorkflowsHome from "./index";
import { workspaces, workspace as workspaceFixture, profile } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { WorkspaceContextProvider } from "State/context";
import { renderWithContext } from "Utils/testing/render";

// Route-module test pattern (see GlobalParameters.spec.tsx): build the same shape the real router
// config uses (app/routes/workflows.tsx - a route carrying loader/action alongside its element,
// matched on the real "/:workspace/workflows" path so the loader/action's `params.workspace` read
// resolves) and hand it to renderWithContext - it detects a <Route> element and uses
// it as-is instead of wrapping it in its usual catch-all, so the loader/action actually run.
describe("WorkflowsHome --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowsHome", async () => {
    const { baseElement } = renderWithContext(
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
          communityUrl: "www.ibm.com",
          user: profile,
          workspaces,
        },
        route: appLink.workflows({ workspace: workspaceFixture.name }),
      }
    );
    // Wait for the workflow list to finish loading (the loader resolves before this component
    // renders, but rtlContextRouterRender's own router still needs a tick to settle it) - the
    // count only appears in the title once the workflows have loaded.
    await screen.findByText("Workflows (4)");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowsHome --- quotas off", () => {
  it("lists the workflows without reading quotas when the quotas flag is off", async () => {
    const { quotas: _quotas, ...workspaceWithoutQuotas } = workspaceFixture;
    renderWithContext(
      <Route
        path={AppPath.Workflows}
        loader={loader}
        action={action}
        element={
          <WorkspaceContextProvider value={{ workspace: workspaceWithoutQuotas }}>
            <WorkflowsHome />
          </WorkspaceContextProvider>
        }
      />,
      {
        contextValue: {
          communityUrl: "www.ibm.com",
          user: profile,
          workspaces,
        },
        features: { WorkspaceQuotasEnabled: false },
        route: appLink.workflows({ workspace: workspaceFixture.name }),
      }
    );

    expect(await screen.findByText("Workflows (4)")).toBeInTheDocument();
    expect(screen.queryByText(/Workflow quota/)).not.toBeInTheDocument();
  });
});

describe("WorkflowsHome --- loader", () => {
  test("resolves the workspace's workflows", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspaceFixture.name })}`);

    const result = await loader({ request, params: { workspace: workspaceFixture.name } });

    expect(result.errorLoading).toBe(false);
    // Five rows: three workflows, one made from a template, and one template the workspace has not used.
    expect(result.workflows.length).toBe(5);
  });
});
