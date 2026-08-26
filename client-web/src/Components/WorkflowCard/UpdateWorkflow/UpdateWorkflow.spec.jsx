import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { workspaces } from "ApiServer/fixtures";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Workflows/Workflows";
import UpdateWorkflow from ".";

const workspace = workspaces.content[0];

const props = {
  workspaceName: workspace.name,
  workflowRef: "test-workflow",
  onCloseModal: () => {},
  type: WorkflowView.Workflow,
};

beforeEach(() => {
  document.body.setAttribute("id", "app");
});

// Route-module test pattern (see GlobalParameters.spec.tsx): UpdateWorkflow renders as a
// descendant of the Workflows route's element (via WorkflowCard.tsx, no nested <Route> in
// between), so its `useFetcher()` submits resolve against whichever route is in context - here,
// the same `<Route path="/:workspace/workflows" action={action}>` shape the real route tree wires
// up (app/routes/workflows.tsx).
function renderUpdateWorkflow() {
  return global.rtlContextRouterRender(
    <Route path="/:workspace/workflows" action={action} element={<UpdateWorkflow {...props} />} />,
    { route: appLink.workflows({ workspace: workspace.name }) }
  );
}

describe("UpdateWorkflow --- Snapshot Test", () => {
  it("Capturing Snapshot of UpdateWorkflow", () => {
    const { baseElement } = renderUpdateWorkflow();
    expect(screen.getByRole("button", { name: /Choose a file or drag one here/i })).toBeInTheDocument();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("UpdateWorkflow --- action", () => {
  // See UpdateWorkflow.tsx's request-shape note: the request only ever carries `workspace` (from
  // the route param) and the full workflow body - matching putApplyWorkflow's URL builder, which
  // only reads `workspace`.
  test("updates a workflow through the mocked API", async () => {
    server.use(
      http.put(serviceUrl.workspace.workflow.putApplyWorkflow({ workspace: workspace.name }), () =>
        HttpResponse.json({}, { status: 200 })
      )
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "update",
        workflow: JSON.stringify({ name: "test-workflow", displayName: "Test Workflow" }),
      }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ ok: true, intent: "update" });
  });

  test("surfaces a failed update without throwing", async () => {
    server.use(
      http.put(serviceUrl.workspace.workflow.putApplyWorkflow({ workspace: workspace.name }), () =>
        HttpResponse.json({}, { status: 500 })
      )
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "update",
        workflow: JSON.stringify({ name: "test-workflow", displayName: "Test Workflow" }),
      }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ ok: false, intent: "update" });
  });
});
