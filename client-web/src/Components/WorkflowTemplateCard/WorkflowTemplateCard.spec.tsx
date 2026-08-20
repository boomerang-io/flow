import React from "react";
import { Response } from "miragejs";
import { Route } from "react-router-dom";
import { startApiServer } from "ApiServer";
import { workflowTemplates } from "ApiServer/fixtures";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/TemplateWorkflows/TemplateWorkflows";
import { WorkflowStatus } from "Types";
import WorkflowTemplateCard from "./index";

// The fixture is the wire shape returned by the mocked workflow-template list endpoint (a
// canvas-editor payload - tasks/dependencies/params in their own loosely-typed shape), which is
// not the full `Workflow` type this card's prop is typed against and WorkflowTemplateCard only
// ever reads name/description/icon off. Rather than spread the mismatched fixture and cast past
// the gap, build a minimal object that satisfies `Workflow` directly, carrying over just the
// display fields this card renders.
const templateFixture = workflowTemplates.content[0];
const props = {
  workflow: {
    name: templateFixture.name,
    displayName: templateFixture.displayName,
    creationDate: templateFixture.creationDate,
    status: WorkflowStatus.Active,
    version: templateFixture.version,
    description: templateFixture.description,
    icon: templateFixture.icon,
    labels: {},
    annotations: {},
    params: [],
    tasks: [],
    changelog: { author: "", reason: "", date: templateFixture.creationDate },
    triggers: {
      event: { enabled: false, conditions: [] },
      github: { enabled: false, conditions: [] },
      manual: { enabled: true, conditions: [] },
      schedule: { enabled: false, conditions: [] },
      webhook: { enabled: false, conditions: [] },
    },
    upgradesAvailable: false,
    workspaces: [],
  },
};

// Route-module test pattern (see GlobalParameters.spec.tsx): this card renders as a descendant
// of the templateWorkflows route's element with no nested <Route> of its own, so its
// `useFetcher()` submits resolve against whichever route is in context - here, the same
// `<Route action={action}>` shape the real route tree wires up. Context (user/workspaces) comes
// from rtlContextRouterRender's own defaults - WorkflowTemplateCard doesn't read AppContext, so
// no extra provider wrap is needed here.
function renderWorkflowTemplateCard() {
  return global.rtlContextRouterRender(<Route path="*" action={action} element={<WorkflowTemplateCard {...props} />} />);
}

let server: any;

beforeEach(() => {
  server = startApiServer({ environment: "test" });
});

afterEach(() => {
  server.shutdown();
});

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = renderWorkflowTemplateCard();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowCard --- action", () => {
  test("deletes a workflow template through the mocked API", async () => {
    server.delete(serviceUrl.template.getWorkflowTemplate({ name: props.workflow.name }), () => ({}));

    const request = new Request("http://localhost/admin/template-workflows", {
      method: "post",
      body: new URLSearchParams({ intent: "delete", name: props.workflow.name }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: true, intent: "delete", name: props.workflow.name });
  });

  test("surfaces a failed delete without throwing", async () => {
    server.delete(serviceUrl.template.getWorkflowTemplate({ name: props.workflow.name }), () => new Response(500, {}, {}));

    const request = new Request("http://localhost/admin/template-workflows", {
      method: "post",
      body: new URLSearchParams({ intent: "delete", name: props.workflow.name }),
    });

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("delete");
  });
});
