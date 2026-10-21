// GET /workspace/{workspace}/insights/tasks?groupBy=param:model&bucket=day&slowest=10 - the period's
// ai task runs per model and day, over the same period as fixtures/insights.js.
const insightsTasks = {
  from: "2019-10-03T00:00:00.000+00:00",
  to: "2020-01-01T00:00:00.000+00:00",
  groupBy: "param:model",
  bucket: "day",
  truncated: false,
  rows: [
    {
      key: "openai/gpt-4o-mini",
      bucket: "2019-12-30T00:00:00.000+00:00",
      taskRuns: 42,
      succeeded: 40,
      failed: 2,
      cancelled: 0,
      meanDuration: 9100,
      p50Duration: 8000,
      p95Duration: 21000,
      maxDuration: 64000,
    },
    {
      key: null,
      bucket: "2019-12-31T00:00:00.000+00:00",
      taskRuns: 3,
      succeeded: 3,
      failed: 0,
      cancelled: 0,
      meanDuration: 1200,
      p50Duration: 1000,
      p95Duration: 2000,
      maxDuration: 2000,
    },
  ],
  slowest: [
    {
      taskRunRef: "6ad84a0e1c2f4b0001a1b2c3",
      workflowRunRef: "5d9e2ea2a1a6b10001e37d11",
      workflowRef: "5c3cf9ffbc521b00011b70aa",
      name: "summarise",
      status: "succeeded",
      startTime: "2019-12-30T10:00:00.000+00:00",
      duration: 64000,
      params: {},
    },
  ],
};

export default insightsTasks;
