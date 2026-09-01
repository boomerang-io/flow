import React from "react";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { RunPhase, RunStatus, WorkflowCanvas, WorkflowRun, WorkflowStatus } from "Types";
import RunHeader from "./RunHeader";

// Replaces RunHeader.spec.jsx, which passed a v3-era `{ workflowExecution, workflow }` prop pair
// that RunHeader has not accepted for some time - it destructured `workflowRun` and threw, and
// the committed snapshot enshrined the resulting "Unexpected Application Error!" page as the
// expected output, so the suite passed while the component never rendered at all. Being .jsx, it
// was also invisible to tsc. Rewritten as .tsx against the real Props.

const workspace = "tyson-workspace";
const runId = "651e4789ab1cb56bc8976ae4";
const workflowName = "test-workflow";

const workflowRun: WorkflowRun = {
  annotations: {
    "boomerang.io/task-deletion": "Never",
    "boomerang.io/task-default-image": "",
    "boomerang.io/workspace-name": "Workspace",
    "boomerang.io/kind": "WorkflowRun",
    "boomerang.io/generation": "1",
  },
  awaitingApproval: false,
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 300190,
  id: runId,
  initiatedByRef: "Tim Bula",
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  tasks: [],
  timeout: 0,
  trigger: "manual",
  workspaces: [],
  workflowName,
  workflowRef: workflowName,
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

const workflow: WorkflowCanvas = {
  id: "651b91a77fbb1a64ab8b7154",
  name: workflowName,
  displayName: "Test Workflow",
  creationDate: "2019-09-03T15:00:00.230+0000",
  status: WorkflowStatus.Active,
  version: 1,
  description: "",
  icon: "bot",
  tasks: [],
  changelog: { author: "", reason: "", date: "2019-09-03T15:00:00.230+0000" },
  triggers: {
    event: { enabled: false, conditions: [] },
    github: { enabled: false, conditions: [] },
    manual: { enabled: true, conditions: [] },
    schedule: { enabled: false, conditions: [] },
    webhook: { enabled: false, conditions: [] },
  },
  upgradesAvailable: false,
  workspaces: [],
  edges: [],
  nodes: [],
  config: [],
};

function renderRunHeader(overrides: Partial<WorkflowRun> = {}, workflowOverrides: Partial<WorkflowCanvas> = {}) {
  // RunHeader uses useFetcher, so it needs to sit on a real route of the data router rather than
  // the helper's catch-all - same shape as app/routes/run.tsx.
  return global.rtlContextRouterRender(
    <Route
      path={AppPath.Run}
      element={
        <RunHeader
          workflow={{ ...workflow, ...workflowOverrides }}
          workflowRun={{ ...workflowRun, ...overrides }}
          version={1}
          executionViewRedirect={() => {}}
        />
      }
    />,
    { route: appLink.execution({ workspace, runId }) },
  );
}

describe("RunHeader --- Snapshot", () => {
  it("Capturing Snapshot of RunHeader", () => {
    const { baseElement } = renderRunHeader();
    expect(baseElement).toMatchSnapshot();
  });
});

describe("RunHeader --- RTL", () => {
  it("renders the run metadata", () => {
    renderRunHeader();
    expect(screen.getByText("Activity detail")).toBeInTheDocument();
    expect(screen.getByText("Tim Bula")).toBeInTheDocument();
    expect(screen.getByText("manual")).toBeInTheDocument();
  });

  it("shows the paused indicator only while the run is paused", () => {
    const { unmount } = renderRunHeader();
    expect(screen.queryByTestId("paused-indicator")).not.toBeInTheDocument();
    unmount();

    renderRunHeader({ paused: true });
    expect(screen.getByTestId("paused-indicator")).toBeInTheDocument();
  });
});

// #359: a schedule-fired run stamps the firing Schedule's id into initiatedByRef (Option A -
// mirrors the retry path's convention, WorkflowRunService.java:930-936). The Schedules page has
// no per-schedule focus route, so this deep-links its existing "workflows" filter instead.
describe("RunHeader --- Initiated by", () => {
  it("links a schedule-triggered run's initiatedByRef to its workflow's schedules", () => {
    renderRunHeader({ trigger: "schedule", initiatedByRef: "651e4789ab1cb56bc8976af0" });

    const link = screen.getByTestId("initiated-by-schedule-link");
    expect(link).toHaveTextContent("651e4789ab1cb56bc8976af0");
    expect(link).toHaveAttribute("href", `/${workspace}/schedules?workflows=${workflowName}`);
  });

  it("keeps a retried run's initiatedByRef as plain text, not a link", () => {
    renderRunHeader({ trigger: "retry", initiatedByRef: "651e4789ab1cb56bc8976ae9" });

    expect(screen.getByText("651e4789ab1cb56bc8976ae9")).toBeInTheDocument();
    expect(screen.queryByTestId("initiated-by-schedule-link")).not.toBeInTheDocument();
  });

  it("falls back to the robot glyph for a schedule-triggered run with no recorded initiatedByRef", () => {
    renderRunHeader({ trigger: "schedule", initiatedByRef: undefined as unknown as string });

    expect(screen.queryByTestId("initiated-by-schedule-link")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "robot" })).toBeInTheDocument();
  });
});

// The Advanced detail modal builds the `kubectl`/`tkn` label selectors the user is invited to
// paste into a terminal, so every value in them has to be real. workflow-ref came from a
// `:workflow` route param that AppPath.Run (/:workspace/activity/:runId) does not supply, so the
// user was shown - and copied - `boomerang.io/workflow-ref=undefined`.
describe("RunHeader --- Advanced detail", () => {
  async function openAdvancedDetail(
    overrides: Partial<WorkflowRun> = {},
    workflowOverrides: Partial<WorkflowCanvas> = {},
  ) {
    const view = renderRunHeader(overrides, workflowOverrides);
    await userEvent.click(screen.getByTestId("advanced-detail-trigger"));
    // "Advanced detail" itself appears twice (the trigger tooltip and the modal header), so key
    // the wait on something only the modal body renders. Text queries rather than role queries
    // throughout this block: react-modal's ariaHideApp puts aria-hidden="true" on the app element
    // - which under this harness is <body> itself - while the modal is open, so every plain
    // byRole query misses the whole tree.
    await screen.findByText("Labels");
    return view;
  }

  it("labels the run with the workflow ref off the run, not an absent route param", async () => {
    await openAdvancedDetail();

    const expectedLabel = `boomerang.io/workflow-ref=${workflowRun.workflowRef}`;
    expect(screen.getByText(expectedLabel)).toBeInTheDocument();
    expect(screen.getByText(`boomerang.io/workflowrun-ref=${runId}`)).toBeInTheDocument();
    expect(screen.queryByText(/workflow-ref=undefined/)).not.toBeInTheDocument();

    // The same selector feeds both copyable commands.
    const commands = screen
      .getAllByRole("textbox", { hidden: true })
      .map((el) => (el as HTMLTextAreaElement).value);
    expect(commands.some((command) => command.includes(`tkn tr list --label ${expectedLabel},`))).toBe(true);
    expect(commands.some((command) => command.includes(`kubectl get pods -l ${expectedLabel},`))).toBe(true);
    expect(commands.every((command) => !command.includes("undefined"))).toBe(true);
  });

  it("includes the workflow's own labels, which are a record rather than an array of pairs", async () => {
    await openAdvancedDetail({}, { labels: { tier: "gold", env: "prod" } });

    expect(screen.getByText("tier=gold")).toBeInTheDocument();
    expect(screen.getByText("env=prod")).toBeInTheDocument();
  });

  it("omits a label whose ref is missing rather than printing undefined", async () => {
    await openAdvancedDetail({ workflowRef: undefined as unknown as string });

    expect(screen.queryByText(/boomerang.io\/workflow-ref=/)).not.toBeInTheDocument();
    expect(screen.getByText(`boomerang.io/workflowrun-ref=${runId}`)).toBeInTheDocument();
  });
});
