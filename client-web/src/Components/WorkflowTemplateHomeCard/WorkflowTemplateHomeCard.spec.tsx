import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { server } from "ApiServer/msw/node";
import { workspaces, workflowTemplates, profile } from "ApiServer/fixtures";
import { AppContextProvider } from "State/context";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Home";
import WorkflowCard from "./index";

const props = {
  template: workflowTemplates.content[0],
  workspaces: workspaces.content,
};

// Route-module test pattern (see GlobalParameters.spec.tsx / WorkflowTemplateCard.spec.tsx):
// this card renders as a descendant of the Home route's element with no nested <Route> of its
// own, so its `useFetcher()` submits resolve against whichever route is in context - here, the
// same `<Route action={action}>` shape the real route tree (app/routes/home.tsx) wires up.
function renderWorkflowCard() {
  return global.rtlContextRouterRender(
    <Route
      path="*"
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
          <WorkflowCard {...props} />
        </AppContextProvider>
      }
    />,
  );
}

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = renderWorkflowCard();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowCard --- action", () => {
  test("creates a workflow from a template through the mocked API", async () => {
    const workspace = workspaces.content[0].name;
    server.use(
      http.post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace }), () =>
        HttpResponse.json({ name: "new-workflow" }),
      ),
    );

    const request = new Request("http://localhost/home", {
      method: "post",
      body: new URLSearchParams({
        intent: "create-workflow-from-template",
        workspace,
        body: JSON.stringify({ ...props.template, name: "New Workflow", description: "", icon: props.template.icon }),
      }),
    });

    const result = await action({ request });

    expect(result).toEqual({
      ok: true,
      intent: "create-workflow-from-template",
      workspace,
      workflow: { name: "new-workflow" },
    });
  });

  test("surfaces a failed create without throwing", async () => {
    const workspace = workspaces.content[0].name;
    server.use(
      http.post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const request = new Request("http://localhost/home", {
      method: "post",
      body: new URLSearchParams({
        intent: "create-workflow-from-template",
        workspace,
        body: JSON.stringify({ ...props.template, name: "New Workflow", description: "", icon: props.template.icon }),
      }),
    });

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("create-workflow-from-template");
  });
});
