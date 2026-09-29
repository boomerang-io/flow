import { parseChartsData } from "./formatData";
import { InsightsRuns } from "../Insights";

const run = (workflowRef: string, workflowName: string): InsightsRuns => ({
  creationDate: "2026-09-29T00:00:00Z",
  duration: 1000,
  status: "succeeded" as InsightsRuns["status"],
  workflowRef,
  workflowName,
});

describe("parseChartsData", () => {
  test("counts runs of a workflow recreated under the same name as one workflow", () => {
    const { executionsCountList } = parseChartsData(
      [run("old-id", "nightly"), run("new-id", "nightly"), run("new-id", "nightly"), run("other-id", "weekly")],
      null,
    );

    expect(executionsCountList).toEqual([
      { label: "nightly", value: 3 },
      { label: "weekly", value: 1 },
    ]);
  });
});
