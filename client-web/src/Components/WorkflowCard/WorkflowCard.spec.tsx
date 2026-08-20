import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { server } from "ApiServer/msw/node";
import { workspaces, workflows } from "ApiServer/fixtures";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Workflows/Workflows";
import { FlowWorkspaceQuotas, Workflow, WorkflowStatus } from "Types";
import WorkflowCard from "./index";

const workspace = workspaces.content[0];
const workflowFixture = workflows.content[0];

// The fixture data below is a loosely-typed .js module (nulls where the type wants numbers,
// missing fields added to these interfaces after the fixture was written), so it's not reused
// directly - a minimal object satisfying the real interfaces is built instead, carrying over just
// the display fields this card reads off the fixture. See WorkflowTemplateCard.spec.tsx for the
// same approach on the Workflow Templates side.
const quotas: FlowWorkspaceQuotas = {
  currentRuns: 0,
  currentWorkflowCount: 0,
  currentConcurrentRuns: 0,
  currentRunTotalDuration: 0,
  currentRunMedianDuration: 0,
  currentWorkflowStorage: 0,
  currentWorkflowRunStorage: 0,
  maxWorkflowCount: 10,
  maxWorkflowRunMonthly: 100,
  maxWorkflowStorage: 100,
  maxWorkflowRunStorage: 100,
  maxWorkflowRunDuration: 100,
  maxConcurrentRuns: 10,
  monthlyResetDate: "2022-01-01T00:00:00.000Z",
};

const workflow: Workflow = {
  // A plain name rather than the fixture's ("Personal - ML Train – Bot Efficiency", spaces + an
  // en-dash) - the action tests below register per-test `server.use()` overrides built from this
  // name via the same serviceUrl builder the request itself uses, and MSW's path matching on a
  // literal (non-`:param`) pattern containing those characters didn't reliably match the encoded
  // outgoing request URL.
  name: "test-workflow",
  displayName: workflowFixture.displayName ?? "Test Workflow",
  creationDate: workflowFixture.creationDate ?? "",
  status: WorkflowStatus.Active,
  version: workflowFixture.version ?? 1,
  description: workflowFixture.description ?? "",
  icon: workflowFixture.icon ?? "bot",
  labels: {},
  annotations: {},
  params: [],
  tasks: [],
  changelog: { author: "", reason: "", date: workflowFixture.creationDate ?? "" },
  triggers: {
    event: { enabled: false, conditions: [] },
    github: { enabled: false, conditions: [] },
    manual: { enabled: true, conditions: [] },
    schedule: { enabled: false, conditions: [] },
    webhook: { enabled: false, conditions: [] },
  },
  upgradesAvailable: false,
  workspaces: [],
};

const props = {
  workspaceName: workspace.name,
  quotas,
  workflow,
  viewType: WorkflowView.Workflow,
};

// Route-module test pattern (see GlobalParameters.spec.tsx): WorkflowCard renders as a descendant
// of the Workflows route's element with no nested <Route> of its own, so its `useFetcher()`
// submits resolve against whichever route is in context - here, the same
// `<Route path="/:workspace/workflows" action={action}>` shape the real route tree wires up
// (app/routes/workflows.tsx), so the action's `params.workspace` read behaves as it does live.
// No explicit AppContextProvider wrap: WorkflowCard's tree doesn't read AppContext (only the
// FlagsProvider rtlContextRouterRender already supplies), and wrapping it with the raw
// (differently-shaped) fixtures the way the previous .jsx spec did is what kept this file off
// typechecking in the first place.
function renderWorkflowCard() {
  return global.rtlContextRouterRender(
    <Route path="/:workspace/workflows" action={action} element={<WorkflowCard {...props} />} />,
    { route: appLink.workflows({ workspace: workspace.name }) },
  );
}

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = renderWorkflowCard();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowCard --- action", () => {
  test("deletes a workflow through the mocked API", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete", workflowName: workflow.name }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ ok: true, intent: "delete" });
  });

  test("surfaces a failed delete without throwing", async () => {
    server.use(
      http.delete(serviceUrl.workspace.workflow.getWorkflow({ workspace: workspace.name, workflow: workflow.name }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete", workflowName: workflow.name }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ ok: false, intent: "delete" });
  });

  test("duplicates a workflow through the mocked API", async () => {
    server.use(
      http.post(
        serviceUrl.workspace.workflow.postDuplicateWorkflow({ workspace: workspace.name, workflow: workflow.name }),
        () => HttpResponse.json({ ...workflow, name: `${workflow.name}-copy` }, { status: 201 }),
      ),
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({ intent: "duplicate", workflowName: workflow.name }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ ok: true, intent: "duplicate" });
  });

  test("executes a workflow through the mocked API", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "execute",
        workflowName: workflow.name,
        body: JSON.stringify({ params: [], trigger: "manual" }),
        redirect: "false",
      }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result.ok).toBe(true);
    expect(result.intent).toBe("execute");
  });
});
