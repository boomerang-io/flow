import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { server } from "ApiServer/msw/node";
import { workspaces, workflowTemplates } from "ApiServer/fixtures";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Home";
import { FlowWorkspaceStatus } from "Constants";
import { FlowWorkspaceSummary, WorkflowTemplate } from "Types";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import WorkflowCard from "./index";

// The fixtures are loosely-typed .js modules (no `markdown`, string-valued annotations, no
// `insights` on the workspace list), so minimal objects satisfying the real interfaces are built
// from the display fields the card reads (name, description, icon; workspace name/displayName)
// rather than reused directly - same approach as WorkflowCard.spec.tsx.
const templateFixture = workflowTemplates.content[0];
const workspaceSummaries: FlowWorkspaceSummary[] = workspaces.content.map((workspace) => ({
  name: workspace.name,
  displayName: workspace.displayName,
  creationDate: workspace.creationDate,
  status: FlowWorkspaceStatus.Active,
  insights: { workflows: 0, members: 0 },
}));
const template: WorkflowTemplate = {
  name: templateFixture.name,
  displayName: templateFixture.displayName,
  icon: templateFixture.icon,
  description: templateFixture.description,
  creationDate: templateFixture.creationDate,
  markdown: "",
  version: templateFixture.version,
  changelog: templateFixture.changelog,
  tasks: [],
  config: [],
};

const props = {
  template,
  workspaces: workspaceSummaries,
};

// Route-module test pattern (see GlobalParameters.spec.tsx / WorkflowTemplateCard.spec.tsx):
// this card renders as a descendant of the Home route's element with no nested <Route> of its
// own, so its `useFetcher()` submits resolve against whichever route is in context - here, the
// same `<Route action={action}>` shape the real route tree (app/routes/home.tsx) wires up.
// No explicit AppContextProvider wrap: the card's tree doesn't read AppContext beyond what
// renderWithContext already supplies (same as WorkflowCard.spec.tsx).
function renderWorkflowCard() {
  return renderWithContext(<Route path="*" action={action} element={<WorkflowCard {...props} />} />);
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

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself is
    // what unwraps it into fetcher.data in real use (see the render-based tests above).
    const result = (await action({ request })) as unknown as { data: { intent: string; error: unknown } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("create-workflow-from-template");
  });
});
