import React from "react";
import { screen } from "@testing-library/react";
import * as fixtures from "ApiServer/fixtures";
import { renderWithContext } from "Utils/testing/render";
import Home from "./Home";
import { EMPTY_RUN_COUNTS, HomeLoaderData } from "./homeLoader";

// Render spec: the loader is covered in Home.loader.spec.ts, so it is stubbed here with a
// hand-built rollup. The workspace list is the profile fixture's three memberships - the shape
// App.tsx feeds AppContext with (summaries carrying `insights`), not the paginated list.
function rollup(overrides: Partial<HomeLoaderData> = {}): HomeLoaderData {
  return {
    dateLabel: "Tuesday 29 September",
    runsToday: { ...EMPTY_RUN_COUNTS, all: 12, succeeded: 9, failed: 2, running: 1 },
    attention: [],
    attentionTotal: 0,
    attentionApprovals: 0,
    attentionManual: 0,
    recentRuns: [],
    totalRuns: 40,
    nextSchedule: null,
    schedulesTotal: 0,
    stats: {},
    degraded: false,
    ...overrides,
  };
}

function renderHome(data: HomeLoaderData, contextValue: Record<string, unknown> = {}) {
  return renderWithContext(<Home />, {
    path: "/home",
    route: "/home",
    loader: () => data,
    contextValue: { name: "Acme Automation", workflowTemplates: [], workspaces: fixtures.profile.teams, ...contextValue },
  });
}

describe("Home", () => {
  test("greets the user with the platform's configured name and the day's summary", async () => {
    renderHome(rollup({ attentionTotal: 1, attentionApprovals: 1 }));

    expect(await screen.findByRole("heading", { level: 1, name: /Welcome back, / })).toBeInTheDocument();
    // The product name comes from context - never a literal in the page.
    expect(screen.getByText(/Tuesday 29 September · Acme Automation/)).toBeInTheDocument();
    expect(screen.getByText(/12 runs today across your 3 workspaces\. 1 approval is waiting for you\./)).toBeInTheDocument();
    expect(screen.getByTestId("home-pulse-runs")).toHaveTextContent("12");
    expect(screen.getAllByTestId("workspace-card")).toHaveLength(3);
  });

  const oneApproval = rollup({
    attentionTotal: 1,
    attentionApprovals: 1,
    attention: [
      {
        id: "a1",
        taskRunRef: "t1",
        workflowRunRef: "r1",
        workflowRef: "w1",
        workspaceRef: "ws",
        status: "submitted",
        type: "approval",
        creationDate: "2020-01-01T00:00:00.000Z",
        taskName: "Approve production deploy",
        workflowName: "release-pipeline",
        workspaceName: "tyson-workspace",
        numberOfApprovals: 0,
        approvalsRequired: 1,
        actioners: [],
        instructions: null,
        workspace: "tyson-workspace",
        workspaceDisplayName: "Tyson Workspace",
      },
    ],
  });
  // The fixture user carries no grants; this one may act on anything.
  const approver = { ...fixtures.profile, permissions: [{ scope: "global", principal: "*", actions: ["**"] }] };

  test("lists the actions waiting on the user with approve and reject for someone who may decide", async () => {
    renderHome(oneApproval, { user: approver });

    expect(await screen.findByRole("heading", { name: "Needs your attention" })).toBeInTheDocument();
    expect(screen.getByText("Approve production deploy")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    // Carbon prefixes a danger button's accessible name with a visually hidden "danger".
    expect(screen.getByRole("button", { name: /Reject/ })).toBeInTheDocument();
  });

  test("offers only the run to someone whose grants do not cover the decision", async () => {
    renderHome(oneApproval);

    expect(await screen.findByRole("heading", { name: "Needs your attention" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View run" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reject/ })).not.toBeInTheDocument();
  });

  test("links each recent run by its workflow name", async () => {
    renderHome(
      rollup({
        recentRuns: [
          {
            id: "run-1",
            workflowName: "release-pipeline",
            workflowRef: "w1",
            workspace: "tyson-workspace",
            workspaceDisplayName: "Tyson Workspace",
            status: "succeeded" as HomeLoaderData["recentRuns"][number]["status"],
            trigger: "manual",
            duration: 61_000,
            creationDate: "2019-12-31T23:00:00.000Z",
          },
        ],
      }),
    );

    await screen.findByRole("heading", { name: "Recent activity" });
    expect(screen.getByRole("link", { name: "release-pipeline" })).toHaveAttribute("href", "/tyson-workspace/activity/run-1");
    expect(screen.getByRole("table", { name: "Recent runs" })).toBeInTheDocument();
  });

  test("shows the getting-started steps and concepts, not the rollup, when the user has no workspace", async () => {
    renderHome(rollup({ totalRuns: 0 }), { workspaces: [] });

    expect(await screen.findByRole("heading", { level: 1, name: /Let's get your first automation running/ })).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Get started" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Key concepts" })).toBeInTheDocument();
    expect(screen.queryByTestId("home-pulse-runs")).not.toBeInTheDocument();
  });

  test("warns when part of the rollup could not be loaded", async () => {
    renderHome(rollup({ degraded: true }));

    expect(await screen.findByText("Some numbers could not be loaded.")).toBeInTheDocument();
  });
});
