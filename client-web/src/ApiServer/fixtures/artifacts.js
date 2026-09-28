// Files an `uploadartifact` task uploaded during a run. `workflowRunRef` ties an entry to a
// WorkflowRun fixture (see workflowExecution.js's `id`); `taskRunRef` ties it to one of that run's
// TaskRun ids so the run view's Artifacts tab can resolve "Uploaded by" - see workflowExecution.js
// for the ids these reference. `status: "uploading"` is intentionally absent - the list routes
// never return it (Artifact.status doc comment, Types/index.tsx).
const artifacts = [
  {
    id: "artifact-1",
    name: "build-output.tar.gz",
    workflowRef: "test-workflow",
    workflowRunRef: "5f8e19ee8f268161b4beb242",
    taskRunRef: "5c36289096052900012cc81e",
    size: 52428800,
    sha256: "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    contentType: "application/gzip",
    status: "available",
    creationDate: "2024-01-10T12:00:00.000Z",
    retentionDays: 30,
    expirationDate: "2024-02-09T12:00:00.000Z",
  },
  {
    id: "artifact-2",
    name: "coverage-report.html",
    workflowRef: "test-workflow",
    workflowRunRef: "5f8e19ee8f268161b4beb242",
    taskRunRef: "5c36289096052900012cc81e",
    size: 10240,
    sha256: "1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c",
    contentType: "text/html",
    status: "expired",
    creationDate: "2023-11-01T09:00:00.000Z",
    retentionDays: 30,
    expirationDate: "2023-12-01T09:00:00.000Z",
  },
  {
    id: "artifact-3",
    name: "release-notes.md",
    workflowRef: "test-workflow-two",
    workflowRunRef: "5f8e19ee8f268161b4beb243",
    taskRunRef: "5c36289096052900012cc900",
    size: 2048,
    sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    contentType: "text/markdown",
    status: "available",
    creationDate: "2024-01-15T08:30:00.000Z",
    retentionDays: 30,
    expirationDate: "2024-02-14T08:30:00.000Z",
  },
];

export default artifacts;
