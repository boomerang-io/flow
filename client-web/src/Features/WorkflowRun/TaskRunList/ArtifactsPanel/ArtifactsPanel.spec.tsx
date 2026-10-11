import React from "react";
import { fireEvent, screen, within } from "@testing-library/react";
import { renderWithRouter } from "Utils/testing/render";
import { Artifact, ArtifactStatus, RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
import ArtifactsPanel from "./ArtifactsPanel";

const taskRun: TaskRun = {
  annotations: { "boomerang.io/position": { x: 0, y: 0 } },
  creationDate: "2019-09-03T15:00:00.230+0000",
  duration: 300190,
  id: "task-run-1",
  labels: {},
  name: "Upload build output",
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
  status: RunStatus.Succeeded,
  statusMessage: "",
  taskRef: "515c8b05-ceb0-470a-a58e-b8740b332a6a",
  timeout: 0,
  type: "uploadartifact",
  workflowRef: "test-workflow",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowRunRef: "run-1",
  workflowName: "test-workflow",
  workspaces: [],
};

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
  id: "run-1",
  initiatedByRef: "",
  labels: {},
  params: [],
  phase: RunPhase.Completed,
  results: [],
  retries: 0,
  startTime: "2019-09-03T15:00:00.230+0000",
  status: RunStatus.Succeeded,
  statusMessage: "",
  tasks: [taskRun],
  timeout: 0,
  trigger: "manual",
  workspaces: [],
  workflowName: "test-workflow",
  workflowRef: "test-workflow",
  workflowRevisionRef: "651cffa3e99fd73f5122879d",
  workflowVersion: 1,
};

const availableArtifact: Artifact = {
  id: "artifact-available",
  name: "build-output.tar.gz",
  workflowRef: "test-workflow",
  workflowRunRef: "run-1",
  taskRunRef: "task-run-1",
  size: 2048,
  sha256: "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  contentType: "application/gzip",
  status: ArtifactStatus.Available,
  creationDate: "2024-01-10T12:00:00.000Z",
  retentionDays: 30,
  expirationDate: "2024-02-09T12:00:00.000Z",
};

const expiredArtifact: Artifact = {
  ...availableArtifact,
  id: "artifact-expired",
  name: "old-report.html",
  status: ArtifactStatus.Expired,
  expirationDate: "2023-12-01T09:00:00.000Z",
};

function renderPanel(artifacts: Array<Artifact>) {
  return renderWithRouter(
    <ArtifactsPanel artifacts={artifacts} workflowRun={workflowRun} workspace="my-workspace" />,
    { action: async () => ({ intent: "deleteArtifact" }) },
  );
}

describe("ArtifactsPanel", () => {
  it("renders one item per artifact, resolving the uploader's task run name", () => {
    renderPanel([availableArtifact, expiredArtifact]);

    expect(screen.getAllByTestId("artifactitem-name")).toHaveLength(2);
    expect(screen.getByText("build-output.tar.gz")).toBeInTheDocument();
    expect(screen.getByText("old-report.html")).toBeInTheDocument();
    expect(screen.getAllByText("Upload build output")).toHaveLength(2);
  });

  it("shows a summary of available size and expired count", () => {
    renderPanel([availableArtifact, expiredArtifact]);

    expect(screen.getByText(/2\.0 KB across 1 artifact/)).toBeInTheDocument();
    expect(screen.getByText(/1 expired/)).toBeInTheDocument();
  });

  it("offers Download only for an available artifact, not an expired one", () => {
    renderPanel([availableArtifact, expiredArtifact]);

    const items = screen.getAllByRole("listitem");
    const availableItem = items.find((item) => within(item).queryByText("build-output.tar.gz"));
    const expiredItem = items.find((item) => within(item).queryByText("old-report.html"));

    expect(within(availableItem as HTMLElement).getByRole("link", { name: "Download" })).toBeInTheDocument();
    expect(within(expiredItem as HTMLElement).queryByRole("link", { name: "Download" })).not.toBeInTheDocument();
  });

  it("shows an empty state when the run has no artifacts", () => {
    renderPanel([]);

    expect(screen.getByText("No artifacts for this run.")).toBeInTheDocument();
    expect(screen.queryAllByTestId("artifactitem-name")).toHaveLength(0);
  });

  it("shows the full SHA-256 in View Details", async () => {
    renderPanel([availableArtifact]);

    fireEvent.click(screen.getByText("View Details"));

    expect(await screen.findByText("SHA-256")).toBeInTheDocument();
    expect(screen.getByText(availableArtifact.sha256)).toBeInTheDocument();
  });
});
