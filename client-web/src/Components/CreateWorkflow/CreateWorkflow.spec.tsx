import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import CreateWorkflow from ".";
import { screen, fireEvent } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { workspaces } from "ApiServer/fixtures";
import { FlowWorkspaceStatus, WorkflowView } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "Features/Workflows/Workflows";
import { FlowWorkspace, FlowWorkspaceQuotas } from "Types";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";

// The fixtures are loosely-typed .js modules (plain-string statuses, nulls where the quota type
// wants numbers, workflows missing fields added since), so minimal objects satisfying the real
// interfaces are built from the fields this component reads (workspace name and quotas, workflow
// names) rather than reused directly - same approach as WorkflowCard.spec.tsx.
const workspaceFixture = workspaces.content[0];
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
const workspace: FlowWorkspace = {
  id: workspaceFixture.id,
  name: workspaceFixture.name,
  displayName: workspaceFixture.displayName,
  creationDate: workspaceFixture.creationDate,
  status: FlowWorkspaceStatus.Active,
  quotas,
  members: [],
  parameters: [],
  approverGroups: [],
};

const props = {
  workspace,
  hasReachedWorkflowLimit: false,
  workflows: [],
  viewType: WorkflowView.Workflow,
};

// Route-module test pattern (see GlobalParameters.spec.tsx): CreateWorkflow renders as a
// descendant of the Workflows route's element with no nested <Route> of its own, so its
// `useFetcher()` submits resolve against whichever route is in context - here, the same
// `<Route path="/:workspace/workflows" action={action}>` shape the real route tree wires up
// (app/routes/workflows.tsx), so the action's `params.workspace` read behaves as it does live.
// No explicit AppContextProvider wrap: CreateWorkflow's tree doesn't read AppContext beyond what
// renderWithContext already supplies (same as WorkflowCard.spec.tsx).
function renderCreateWorkflow() {
  return renderWithContext(
    <Route path="/:workspace/workflows" action={action} element={<CreateWorkflow {...props} />} />,
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

    // Success is returned as the plain payload, not wrapped in data() - see actionResult.ts.
    const result = (await action({ request, params: { workspace: workspace.name } })) as unknown as {
      intent: string;
    };

    expect(isActionError(result)).toBe(false);
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

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({ request, params: { workspace: workspace.name } })) as unknown as {
      data: { intent: string };
    };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("create");
  });
});
