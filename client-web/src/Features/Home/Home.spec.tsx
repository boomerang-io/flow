import React from "react";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
    degraded: { runsToday: false, attention: false, schedules: false, recentRuns: false },
    ...overrides,
  };
}

function renderHome(
  data: HomeLoaderData,
  contextValue: Record<string, unknown> = {},
  action: (args: { request: Request }) => Promise<unknown> = async () => null,
) {
  return renderWithContext(<Home />, {
    path: "/home",
    route: "/home",
    loader: () => data,
    action,
    contextValue: { name: "Acme Automation", workflowTemplates: [], workspaces: fixtures.profile.workspaces, ...contextValue },
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

  test("marks only the numbers that could not be loaded, never showing them as zero", async () => {
    renderHome(
      rollup({
        runsToday: EMPTY_RUN_COUNTS,
        recentRuns: [],
        degraded: { runsToday: false, attention: true, schedules: false, recentRuns: true },
      }),
    );

    const attention = await screen.findByTestId("home-pulse-attention");
    expect(attention).toHaveTextContent("---");
    expect(attention).toHaveTextContent("Could not load. Refresh to try again.");
    expect(screen.getByTestId("home-pulse-runs")).not.toHaveTextContent("Could not load");
    expect(screen.getByText("Recent runs could not be loaded. Refresh to try again.")).toBeInTheDocument();
    expect(screen.queryByText(/No runs yet\. Run a workflow/)).not.toBeInTheDocument();
    // The hero states the runs it read and says nothing about the actions it could not read.
    expect(screen.getByText("No runs yet today across your 3 workspaces.")).toBeInTheDocument();
  });

  test("says today's runs could not be loaded rather than that there were none", async () => {
    renderHome(rollup({ degraded: { runsToday: true, attention: false, schedules: false, recentRuns: false } }));

    expect(await screen.findByText(/Today's runs across your 3 workspaces could not be loaded\./)).toBeInTheDocument();
    expect(screen.getByTestId("home-pulse-runs")).toHaveTextContent("---");
  });

  test("closes Get started for good: the row goes and the close is sent to be saved", async () => {
    const intents: Array<string> = [];
    renderHome(rollup({ totalRuns: 0 }), {}, async ({ request }) => {
      intents.push(String((await request.formData()).get("intent")));
      return { intent: "hide-get-started" };
    });

    expect(await screen.findByRole("list", { name: "Get started" })).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("home-get-started-close"));

    expect(await screen.findByTestId("home-pulse-runs")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Get started" })).not.toBeInTheDocument();
    expect(intents).toEqual(["hide-get-started"]);
  });

  test("keeps Get started closed for a user who closed it before", async () => {
    renderHome(rollup({ totalRuns: 0 }), { user: { ...fixtures.profile, settings: { isShowGetStarted: false } } });

    expect(await screen.findByTestId("home-pulse-runs")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Get started" })).not.toBeInTheDocument();
  });

  test("starts a workflow from templates only, newest first", async () => {
    renderHome(rollup(), {
      workflowTemplates: [
        { name: "older", displayName: "Older example", creationDate: "2022-05-06T00:00:00Z", icon: "bot", description: "" },
        { name: "ai", displayName: "Summarise a web page with AI", creationDate: "2026-10-19T00:00:00Z", icon: "bot", description: "" },
      ],
    });

    const list = await screen.findByRole("region", { name: "Start a workflow" });
    expect(within(list).getAllByTestId("workflow-card-title").map((title) => title.textContent)).toEqual([
      "Summarise a web page with AI",
      "Older example",
    ]);
    expect(screen.queryByText("Blank workflow")).not.toBeInTheDocument();
  });
});
