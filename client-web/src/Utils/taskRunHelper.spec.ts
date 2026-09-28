import {
  foreachEmptyParentTask,
  foreachInvalidParentTask,
  foreachItemTasks,
  foreachParentTask,
  foreachWorkflowRun,
} from "Utils/testing/fixtures/foreachRun";
import type { WorkflowNode } from "Types";
import {
  findTaskRunByName,
  foreachItemRuns,
  foreachItemSuffix,
  foreachParentSummary,
  foreachTaskNames,
  formatForeachSummary,
  summarizeForeachItems,
} from "./taskRunHelper";

describe("taskRunHelper", () => {
  it("finds the parent of a for-each task by name, never an item", () => {
    expect(findTaskRunByName(foreachWorkflowRun.tasks, "locate")).toBe(foreachParentTask);
    // An item's own name is not a node name - the diagram never asks for it.
    expect(findTaskRunByName(foreachWorkflowRun.tasks, "locate[0]")).toBeUndefined();
    expect(findTaskRunByName(foreachWorkflowRun.tasks, "stage")?.id).toBe("foreach-stage");
    expect(findTaskRunByName(foreachWorkflowRun.tasks, undefined)).toBeUndefined();
  });

  it("lists a parent's items in item order", () => {
    expect(foreachItemRuns(foreachWorkflowRun.tasks, foreachParentTask.id).map((t) => t.name)).toEqual([
      "locate[0]",
      "locate[1]",
      "locate[2]",
    ]);
    expect(foreachItemRuns(foreachWorkflowRun.tasks, "foreach-stage")).toEqual([]);
  });

  it("counts items by outcome and leaves zero running or failed out of the summary", () => {
    const summary = summarizeForeachItems(foreachItemTasks);
    expect(summary).toEqual({ total: 3, succeeded: 1, running: 1, failed: 1 });
    expect(formatForeachSummary(summary)).toBe("3 items · 1 succeeded · 1 running · 1 failed");
    expect(formatForeachSummary({ total: 1, succeeded: 1, running: 0, failed: 0 })).toBe("1 item · 1 succeeded");
  });

  it("names the for-each tasks from the workflow definition", () => {
    const nodes = [
      { data: { name: "stage" } },
      { data: { name: "locate", foreach: { items: "$(params.repos)" } } },
    ] as unknown as Array<WorkflowNode>;
    expect(foreachTaskNames(nodes)).toEqual(new Set(["locate"]));
    expect(foreachTaskNames(undefined)).toEqual(new Set());
  });

  it("summarises a parent with no items by why it failed, 0 items once ended, or nothing yet", () => {
    expect(foreachParentSummary(foreachParentTask, foreachItemTasks)).toBe(
      "3 items · 1 succeeded · 1 running · 1 failed",
    );
    expect(foreachParentSummary(foreachEmptyParentTask, [])).toBe("0 items");
    expect(foreachParentSummary(foreachInvalidParentTask, [])).toBe(
      "The for-each items did not resolve to a JSON array.",
    );
    expect(foreachParentSummary({ ...foreachInvalidParentTask, statusMessage: "" }, [])).toBe("ForeachItemsInvalid");
    expect(foreachParentSummary(foreachParentTask, [])).toBeUndefined();
  });

  it("takes the item's own part of its name", () => {
    expect(foreachItemSuffix(foreachItemTasks[0], "locate")).toBe("[2]");
  });
});
