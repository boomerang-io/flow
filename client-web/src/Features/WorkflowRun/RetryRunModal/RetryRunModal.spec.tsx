import React from "react";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AppPath, appLink } from "Config/appConfig";
import { RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
import { actionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import RetryRunModal from "./RetryRunModal";

// No toast container renders in a spec (react-toastify needs one), so the toasts the modal raises
// are read off notify's calls.
const { notify } = vi.hoisted(() => ({ notify: vi.fn() }));
vi.mock("@boomerang-io/carbon-addons-boomerang-react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@boomerang-io/carbon-addons-boomerang-react")>()),
  notify,
}));

beforeEach(() => {
  notify.mockClear();
});

const workspace = "tyson-workspace";
const runId = "651e4789ab1cb56bc8976ae4";

function taskRun(name: string, status: RunStatus, type = "template"): TaskRun {
  return {
    annotations: { "boomerang.io/position": { x: 0, y: 0 } },
    creationDate: "2019-09-03T15:00:00.230+0000",
    duration: 1000,
    id: `${name}-id`,
    labels: {},
    name,
    params: [],
    phase: RunPhase.Completed,
    results: [],
    retries: 0,
    spec: {
      arguments: null,
      command: null,
      debug: false,
      deletion: null,
      envs: null,
      image: null,
      timeout: 0,
      script: null,
      workingDir: null,
    },
    startTime: "2019-09-03T15:00:00.230+0000",
    status,
    statusMessage: "",
    taskRef: "",
    timeout: 0,
    type,
    workflowRef: "wf",
    workflowRevisionRef: "rev",
    workflowRunRef: runId,
    workflowName: "test-workflow",
  };
}

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
  initiatedByRef: "",
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  tasks: [
    taskRun("start", RunStatus.Succeeded, "start"),
    taskRun("parse", RunStatus.Succeeded),
    taskRun("chair", RunStatus.Succeeded),
    taskRun("end", RunStatus.Succeeded, "end"),
  ],
  timeout: 0,
  trigger: "manual",
  workflowName: "test-workflow",
  workflowRef: "wf",
  workflowRevisionRef: "rev",
  workflowVersion: 1,
};

function renderModal(overrides: Partial<WorkflowRun> = {}, initialNodes: Array<string> = []) {
  // The modal submits through useFetcher, so it sits on the run route as the page does.
  return renderWithContext(
    <Route
      path={AppPath.Run}
      element={
        <RetryRunModal
          workflowRun={{ ...workflowRun, ...overrides }}
          isOpen
          initialNodes={initialNodes}
          onClose={() => {}}
          executionViewRedirect={() => {}}
        />
      }
    />,
    { route: appLink.execution({ workspace, runId }) },
  );
}

// react-modal marks the app element aria-hidden while a modal is open, so queries go by text and
// test id rather than role, as the Advanced detail tests in RunHeader.spec.tsx do.
describe("RetryRunModal", () => {
  it("offers the three scopes and keeps 'failed' out of reach when every task succeeded", async () => {
    renderModal();

    expect(await screen.findByText("Everything, from the start")).toBeInTheDocument();
    expect(screen.getByLabelText("The tasks that did not succeed, and everything after them")).toBeDisabled();
    expect(screen.getByLabelText("Selected tasks, and everything after them")).toBeEnabled();
    expect(screen.getByLabelText("Everything, from the start")).toBeChecked();
  });

  it("offers 'failed' once a task did not succeed", async () => {
    renderModal({
      status: RunStatus.Failed,
      tasks: [taskRun("start", RunStatus.Succeeded, "start"), taskRun("parse", RunStatus.Failed)],
    });

    expect(await screen.findByLabelText("The tasks that did not succeed, and everything after them")).toBeEnabled();
  });

  it("opens on 'selected tasks' with the task a row passed in", async () => {
    renderModal({}, ["chair"]);

    expect(await screen.findByLabelText("Selected tasks, and everything after them")).toBeChecked();
    // The multi-select shows a count of selected tasks; one preselected task enables the submit.
    expect(screen.getByText("Tasks to retry from")).toBeInTheDocument();
    expect(screen.getByTestId("retry-run-submit")).toBeEnabled();
  });

  it("warns that workspace files are not carried over when retrying from failed tasks", async () => {
    renderModal({
      status: RunStatus.Failed,
      tasks: [taskRun("start", RunStatus.Succeeded, "start"), taskRun("parse", RunStatus.Failed)],
      workspace: { size: "1Gi", mountPath: "/workspace" },
    });

    await userEvent.click(await screen.findByLabelText("The tasks that did not succeed, and everything after them"));

    expect(await screen.findByText("Workspace files are not carried over.")).toBeInTheDocument();
  });

  it("warns that workspace files are not carried over when retrying from selected tasks", async () => {
    renderModal({ workspace: { size: "1Gi", mountPath: "/workspace" } }, ["chair"]);

    expect(await screen.findByText("Workspace files are not carried over.")).toBeInTheDocument();
  });

  it("does not warn about workspace files when re-running everything", async () => {
    renderModal({ workspace: { size: "1Gi", mountPath: "/workspace" } });

    expect(await screen.findByLabelText("Everything, from the start")).toBeChecked();
    expect(screen.queryByText("Workspace files are not carried over.")).not.toBeInTheDocument();
  });

  it("does not warn about workspace files when the run has no workspace", async () => {
    renderModal({}, ["chair"]);

    expect(await screen.findByLabelText("Selected tasks, and everything after them")).toBeChecked();
    expect(screen.queryByText("Workspace files are not carried over.")).not.toBeInTheDocument();
  });

  it("says an active origin will be cancelled", async () => {
    renderModal({ status: RunStatus.Waiting, phase: RunPhase.Running });

    expect(await screen.findByText("This run is still active.")).toBeInTheDocument();
  });
});

describe("RetryRunModal --- a refused retry", () => {
  // A refused retry created no run: the modal stays where it is and says why.
  it("stays on the run and says the retry was refused", async () => {
    const executionViewRedirect = vi.fn();
    renderWithContext(
      <Route
        path={AppPath.Run}
        action={() =>
          actionError({
            intent: "retry",
            error: { title: "Quota Exceeded", message: "A quota has been exceeded. Queued runs: 20 / 20" },
          })
        }
        element={
          <RetryRunModal
            workflowRun={workflowRun}
            isOpen
            initialNodes={[]}
            onClose={() => {}}
            executionViewRedirect={executionViewRedirect}
          />
        }
      />,
      { route: appLink.execution({ workspace, runId }) },
    );

    await userEvent.click(await screen.findByText("Retry run", { selector: "button" }));

    await waitFor(() => expect(notify).toHaveBeenCalled());
    expect(notify.mock.calls.map(([toast]) => toast.props)).toEqual([
      expect.objectContaining({ kind: "error", title: "Quota Exceeded" }),
    ]);
    expect(executionViewRedirect).not.toHaveBeenCalled();
  });
});

describe("RetryRunModal --- a retry that waits for a slot", () => {
  function openModalAnswering(result: Record<string, unknown>) {
    const executionViewRedirect = vi.fn();
    renderWithContext(
      <Route
        path={AppPath.Run}
        action={() => result}
        element={
          <RetryRunModal
            workflowRun={workflowRun}
            isOpen
            initialNodes={[]}
            onClose={() => {}}
            executionViewRedirect={executionViewRedirect}
          />
        }
      />,
      { route: appLink.execution({ workspace, runId }) },
    );
    return executionViewRedirect;
  }

  it("says the retry is in the queue, not that it failed or started, and opens it", async () => {
    const executionViewRedirect = openModalAnswering({ intent: "retry", workflowRunRef: "retried-run", queued: true });

    await userEvent.click(await screen.findByText("Retry run", { selector: "button" }));

    await waitFor(() => expect(executionViewRedirect).toHaveBeenCalledWith({ workflowRunRef: "retried-run" }));
    expect(notify.mock.calls.map(([toast]) => toast.props)).toEqual([
      expect.objectContaining({
        kind: "info",
        title: "In the queue",
        subtitle: "Every run slot is busy. Your retry starts as soon as one frees up. You’re on the new run.",
      }),
    ]);
  });

  it("still says a retry that started was started", async () => {
    const executionViewRedirect = openModalAnswering({ intent: "retry", workflowRunRef: "retried-run" });

    await userEvent.click(await screen.findByText("Retry run", { selector: "button" }));

    await waitFor(() => expect(executionViewRedirect).toHaveBeenCalled());
    expect(notify.mock.calls.map(([toast]) => toast.props.title)).toEqual(["Retry started"]);
  });
});
