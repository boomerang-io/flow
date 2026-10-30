import React from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { server } from "ApiServer/msw/node";
import { workspaces, workflows } from "ApiServer/fixtures";
import { WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Workflows/Workflows";
import { FlowWorkspaceQuotas, Workflow, WorkflowStatus } from "Types";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
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
  currentRunAverageDuration: 0,
  currentArtifactStorage: 0,
  maxWorkflowCount: 10,
  maxWorkflowRunMonthly: 100,
  maxWorkspaceStorage: 100,
  maxWorkflowRunDuration: 100,
  maxArtifactStorage: 5,
  artifactRetentionDays: 30,
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
// FlagsProvider renderWithContext already supplies), and wrapping it with the raw
// (differently-shaped) fixtures the way the previous .jsx spec did is what kept this file off
// typechecking in the first place.
function renderWorkflowCard(overrides: Partial<Workflow> = {}, workspaceName: string = props.workspaceName) {
  return renderWithContext(
    <Route
      path="/:workspace/workflows"
      action={action}
      element={<WorkflowCard {...props} workspaceName={workspaceName} workflow={{ ...workflow, ...overrides }} />}
    />,
    { route: appLink.workflows({ workspace: workspace.name }) },
  );
}

describe("WorkflowCard --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowCard", () => {
    const { baseElement } = renderWorkflowCard();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowCard --- templates", () => {
  it("shows a template the workspace does not own as an available, read-only outline", () => {
    renderWorkflowCard({ type: "template" });

    expect(screen.getByText("Template")).toBeInTheDocument();
    expect(screen.getByText("Read-only")).toBeInTheDocument();
    expect(screen.getByText("Available")).toBeInTheDocument();
    expect(screen.getByTestId("workflow-card")).toHaveAttribute("data-available", "true");
    expect(screen.getByText("Run it")).toBeInTheDocument();
  });

  it("shows a workflow that follows a template as the workspace's own, with the tag and the status dot", () => {
    renderWorkflowCard({ type: "workflow", templateRef: { name: "shared-nightly-report" } });

    expect(screen.getByText("Template")).toBeInTheDocument();
    expect(screen.getByText("Read-only")).toBeInTheDocument();
    expect(screen.queryByText("Available")).not.toBeInTheDocument();
    expect(screen.getByTestId("workflow-card")).not.toHaveAttribute("data-available");
  });

  it("shows a plain workflow with no template tag", () => {
    renderWorkflowCard({ type: "workflow" });

    expect(screen.queryByText("Template")).not.toBeInTheDocument();
    expect(screen.queryByText("Read-only")).not.toBeInTheDocument();
    expect(screen.queryByText("Available")).not.toBeInTheDocument();
  });
});

async function openMenu() {
  await userEvent.click(screen.getByRole("button", { name: /overflow/i }));
}

describe("WorkflowCard --- system workspace", () => {
  it("tags a template with no read-only marker", () => {
    renderWorkflowCard({ type: "template" }, "system");

    expect(screen.getByText("Template")).toBeInTheDocument();
    expect(screen.queryByText("Read-only")).not.toBeInTheDocument();
    expect(screen.queryByText("Available")).not.toBeInTheDocument();
    expect(screen.getByTestId("workflow-card")).not.toHaveAttribute("data-available");
  });

  it("tags a starter", () => {
    renderWorkflowCard({ type: "starter" }, "system");

    expect(screen.getByText("Starter")).toBeInTheDocument();
    expect(screen.queryByText("Read-only")).not.toBeInTheDocument();
  });

  it("leaves a plain workflow untagged", () => {
    renderWorkflowCard({ type: "workflow" }, "system");

    expect(screen.queryByText("Template")).not.toBeInTheDocument();
    expect(screen.queryByText("Starter")).not.toBeInTheDocument();
  });

  it("offers a template and a starter on a plain workflow", async () => {
    renderWorkflowCard({ type: "workflow" }, "system");
    await openMenu();

    expect(screen.getByText("Make a template")).toBeInTheDocument();
    expect(screen.getByText("Make a starter")).toBeInTheDocument();
    expect(screen.queryByText("Make a workflow")).not.toBeInTheDocument();
  });

  it("offers a workflow on a template", async () => {
    renderWorkflowCard({ type: "template" }, "system");
    await openMenu();

    expect(screen.getByText("Make a workflow")).toBeInTheDocument();
    expect(screen.queryByText("Make a template")).not.toBeInTheDocument();
    expect(screen.queryByText("Make a starter")).not.toBeInTheDocument();
  });

  it("offers a workflow on a starter and asks to confirm", async () => {
    renderWorkflowCard({ type: "starter" }, "system");
    await openMenu();
    await userEvent.click(screen.getByText("Make a workflow"));

    expect(await screen.findByText("Make this a workflow?")).toBeInTheDocument();
  });

  it("asks to confirm before making a workflow a template", async () => {
    renderWorkflowCard({ type: "workflow" }, "system");
    await openMenu();
    await userEvent.click(screen.getByText("Make a template"));

    expect(await screen.findByText("Make this workflow a template?")).toBeInTheDocument();
  });

  it("offers no kind items outside the system workspace", async () => {
    renderWorkflowCard({ type: "workflow" });
    await openMenu();

    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.queryByText("Make a template")).not.toBeInTheDocument();
    expect(screen.queryByText("Make a starter")).not.toBeInTheDocument();
    expect(screen.queryByText("Make a workflow")).not.toBeInTheDocument();
  });
});

describe("WorkflowCard --- action", () => {
  test("changes the kind of a workflow through the mocked API", async () => {
    let patched: unknown = null;
    server.use(
      http.patch(
        serviceUrl.workspace.workflow.patchWorkflow({ workspace: "system", workflow: workflow.name }),
        async ({ request }) => {
          patched = await request.json();
          return HttpResponse.json({ ...workflow, type: "template" });
        },
      ),
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: "system" })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "changeKind",
        body: JSON.stringify({ workflow: workflow.name, type: "template" }),
      }),
    });

    const result = await action({ request, params: { workspace: "system" } });

    expect(result).toEqual({ intent: "changeKind" });
    expect(patched).toEqual({ type: "template" });
  });

  test("surfaces a template that is still linked without throwing", async () => {
    server.use(
      http.patch(
        serviceUrl.workspace.workflow.patchWorkflow({ workspace: "system", workflow: workflow.name }),
        () => HttpResponse.json({ code: 1, message: "Template is still linked" }, { status: 409 }),
      ),
    );

    const request = new Request(`http://localhost${appLink.workflows({ workspace: "system" })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "changeKind",
        body: JSON.stringify({ workflow: workflow.name, type: "workflow" }),
      }),
    });

    const result = (await action({ request, params: { workspace: "system" } })) as unknown as {
      data: { intent: string };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("changeKind");
  });

  test("deletes a workflow through the mocked API", async () => {
    const request = new Request(`http://localhost${appLink.workflows({ workspace: workspace.name })}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete", workflowName: workflow.name }),
    });

    const result = await action({ request, params: { workspace: workspace.name } });

    expect(result).toEqual({ intent: "delete" });
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

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({ request, params: { workspace: workspace.name } })) as unknown as {
      data: { intent: string };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("delete");
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

    expect(result).toEqual({ intent: "duplicate" });
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

    const result = (await action({ request, params: { workspace: workspace.name } })) as unknown as {
      intent: string;
    };

    expect(isActionError(result)).toBe(false);
    expect(result.intent).toBe("execute");
  });
});
