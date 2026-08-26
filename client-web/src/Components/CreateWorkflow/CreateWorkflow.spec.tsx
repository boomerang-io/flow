import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import CreateWorkflow from ".";
import { screen, fireEvent } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { workspaces, workflows, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Workflows/Workflows";

const workspace = workspaces.content[0];

const props = {
  workspace,
  hasReachedWorkflowLimit: false,
  workflows: workflows.content,
  viewType: WorkflowView.Workflow,
};

// Route-module test pattern (see GlobalParameters.spec.tsx): CreateWorkflow renders as a
// descendant of the Workflows route's element with no nested <Route> of its own, so its
// `useFetcher()` submits resolve against whichever route is in context - here, the same
// `<Route path="/:workspace/workflows" action={action}>` shape the real route tree wires up
// (app/routes/workflows.tsx), so the action's `params.workspace` read behaves as it does live.
function renderCreateWorkflow() {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/workflows"
      action={action}
      element={
        <AppContextProvider
          value={{
            isTutorialActive: false,
            setIsTutorialActive: () => {},
            user: profile,
            workspaces,
          }}
        >
          <CreateWorkflow {...props} />{" "}
        </AppContextProvider>
      }
    />,
    { route: appLink.workflows({ workspace: workspace.name }) },
  );
}

describe("CreateWorkflow --- Snapshot Test", () => {
  test("Capturing Snapshot of CreateWorkflow", () => {
    const { baseElement } = renderCreateWorkflow();
    fireEvent.click(screen.getByText(/Create a new workflow/i));
    expect(baseElement).toMatchSnapshot();
  });
});

describe("CreateWorkflow --- action", () => {
  test("creates a workflow through the mocked API", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "create",
        viewType: WorkflowView.Workflow,
        workflow: JSON.stringify({ name: "new-workflow", displayName: "New Workflow", description: "", icon: "bot" }),
      }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result.ok).toBe(true);
    expect(result.intent).toBe("create");
  });

  test("surfaces a failed create without throwing", async () => {
    server.use(
      http.post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace: workspace.name }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "create",
        viewType: WorkflowView.Workflow,
        workflow: JSON.stringify({ name: "new-workflow", displayName: "New Workflow", description: "", icon: "bot" }),
      }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("create");
  });
});
