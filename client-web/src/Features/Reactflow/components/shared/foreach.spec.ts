import { foreachInitialValues, foreachItemsError, splitForeachValues, taskNameSchema } from "./foreach";

describe("for-each items", () => {
  it("accepts one reference or a JSON array", () => {
    expect(foreachItemsError("$(tasks.stage-findings.results.batches)")).toBeUndefined();
    expect(foreachItemsError("  $(params.repos) ")).toBeUndefined();
    expect(foreachItemsError('["a", {"b": 1}, 3]')).toBeUndefined();
  });

  it("rejects empty values, non-arrays and text around a reference", () => {
    expect(foreachItemsError("")).toBe("Enter the items");
    expect(foreachItemsError('{"a": 1}')).toMatch(/JSON array/);
    expect(foreachItemsError("prefix $(params.repos)")).toMatch(/JSON array/);
    expect(foreachItemsError("$(params.a) $(params.b)")).toMatch(/JSON array/);
  });

  it("leaves the item count to the backend, which checks it against the admin setting on save", () => {
    expect(foreachItemsError(JSON.stringify(Array.from({ length: 1000 }, (_, i) => i)))).toBeUndefined();
  });

  it("stores a reference as a string and a literal as an array, and drops the setting when off", () => {
    expect(splitForeachValues({ foreachEnabled: true, foreachItems: "$(params.repos)", url: "x" })).toEqual({
      foreach: { items: "$(params.repos)" },
      rest: { url: "x" },
    });
    expect(splitForeachValues({ foreachEnabled: true, foreachItems: "[1, 2]" }).foreach).toEqual({ items: [1, 2] });
    expect(splitForeachValues({ foreachEnabled: false, foreachItems: "[1, 2]", url: "x" })).toEqual({
      foreach: undefined,
      rest: { url: "x" },
    });
  });

  it("reads a stored setting back into the form", () => {
    expect(foreachInitialValues(undefined)).toEqual({ foreachEnabled: false, foreachItems: "" });
    expect(foreachInitialValues({ items: ["a", "b"] })).toEqual({ foreachEnabled: true, foreachItems: '["a","b"]' });
  });
});

describe("task names", () => {
  it("reserves [ and ] for the items of a for-each task", async () => {
    const schema = taskNameSchema(["Existing"]);
    await expect(schema.validate("Build 2")).resolves.toBe("Build 2");
    await expect(schema.validate("Build[0]")).rejects.toThrow("Task names cannot contain [ or ]");
    await expect(schema.validate("Build ]")).rejects.toThrow("Task names cannot contain [ or ]");
    await expect(schema.validate("Existing")).rejects.toThrow("Enter a unique value for task name");
  });
});
