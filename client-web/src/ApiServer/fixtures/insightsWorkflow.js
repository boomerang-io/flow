// GET /workspace/{workspace}/insights/workflow/{workflow} - one workflow's task timings and
// failure groups over the same period as fixtures/insights.js.
const insightsWorkflow = {
  workflowRef: "5c3cf9ffbc521b00011b70aa",
  workflowName: "cheer-finding-verify",
  from: "2019-10-03T00:00:00.000+00:00",
  to: "2020-01-01T00:00:00.000+00:00",
  runs: 18,
  p50QueueWait: 4000,
  p95QueueWait: 38000,
  retriedRuns: 2,
  tasks: [
    { name: "fetch-findings", taskRef: "http-call", runs: 18, failed: 1, p50Duration: 12000, p95Duration: 30000 },
    { name: "run-verifier", taskRef: "shell", runs: 17, failed: 3, p50Duration: 125000, p95Duration: 3480000 },
    { name: "await-approval", taskRef: "approval", runs: 14, failed: 0, p50Duration: 41000, p95Duration: 600000 },
    { name: "post-results", taskRef: "http-call", runs: 13, failed: 0, p50Duration: 8000, p95Duration: 15000 },
  ],
  failures: [
    {
      status: "timedout",
      taskName: "run-verifier",
      reason: "Task exceeded its timeout of 60 minutes",
      count: 3,
      lastRunRef: "63c3831e65413043cf2286b7",
      lastDate: "2019-12-31T21:00:00.000+00:00",
    },
    {
      status: "failed",
      taskName: "fetch-findings",
      reason: "HTTP 502 from findings API",
      count: 1,
      lastRunRef: "63c3831e65413043cf2286b9",
      lastDate: "2019-12-29T10:00:00.000+00:00",
    },
    {
      status: "cancelled",
      taskName: "await-approval",
      reason: null,
      count: 1,
      lastRunRef: "63c3831e65413043cf2286c0",
      lastDate: "2019-12-27T10:00:00.000+00:00",
    },
  ],
};

export default insightsWorkflow;
