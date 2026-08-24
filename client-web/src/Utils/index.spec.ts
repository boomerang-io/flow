import { labelStringsToRecord } from "./index";

// `Creatable createKeyValuePair` emits "key:value" strings (and rejects colons in either half),
// while every labels-carrying API model takes a Record<string, string> - backend
// `Map<String, String> labels` on WorkflowSchedule/WorkflowScheduleEntity.
describe("labelStringsToRecord", () => {
  test("converts the Creatable's key:value strings into a record", () => {
    expect(labelStringsToRecord(["level:important", "env:prod"])).toEqual({
      level: "important",
      env: "prod",
    });
  });

  test("returns an empty record for an empty, missing or non-array input", () => {
    expect(labelStringsToRecord([])).toEqual({});
    expect(labelStringsToRecord(undefined)).toEqual({});
    expect(labelStringsToRecord(null)).toEqual({});
  });

  test("splits on the first colon only", () => {
    expect(labelStringsToRecord(["url:https://example.com"])).toEqual({ url: "https://example.com" });
  });

  test("drops entries with no colon or an empty key rather than writing a malformed label", () => {
    expect(labelStringsToRecord(["nocolon", ":novalue", "good:one"])).toEqual({ good: "one" });
  });

  test("keeps an empty value", () => {
    expect(labelStringsToRecord(["key:"])).toEqual({ key: "" });
  });

  test("last entry wins on a duplicate key", () => {
    expect(labelStringsToRecord(["env:dev", "env:prod"])).toEqual({ env: "prod" });
  });
});
