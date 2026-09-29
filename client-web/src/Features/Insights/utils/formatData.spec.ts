import { parseChartsData } from "./formatData";
import { InsightsRuns } from "../Insights";

const run = (workflowRef: string, workflowName: string, status = "succeeded"): InsightsRuns => ({
  creationDate: "2026-09-29T00:00:00Z",
  duration: 1000,
  status: status as InsightsRuns["status"],
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

  test("the status donut counts every run, timed-out runs included", () => {
    const runs = [run("a", "nightly"), run("a", "nightly", "timedout"), run("a", "nightly", "failed")];

    const { donutData } = parseChartsData(runs, null);

    expect(donutData.reduce((total, slice) => total + slice.value, 0)).toBe(runs.length);
    expect(donutData.find((slice) => slice.group === "Timed Out")?.value).toBe(1);
  });
});
