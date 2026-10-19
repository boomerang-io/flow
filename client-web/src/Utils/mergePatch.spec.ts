import { REDACTED } from "Constants";
import { hasChanged, toMergePatch } from "./mergePatch";

describe("toMergePatch", () => {
  test("carries only the members that changed, never the name or id", () => {
    const original = { id: "1", name: "region", label: "Region", description: "old", value: "eu" };
    const edited = { id: "1", name: "region", label: "Region", description: "new", value: "eu" };

    expect(toMergePatch(original, edited)).toEqual({ description: "new" });
  });

  test("leaves an untouched secret out and keeps a cleared one", () => {
    const original = { name: "token", type: "password", value: REDACTED };

    expect(toMergePatch(original, { name: "token", type: "password", value: REDACTED })).toEqual({});
    expect(toMergePatch(original, { name: "token", type: "password", value: "" })).toEqual({ value: "" });
    expect(toMergePatch(original, { name: "token", type: "password", value: "new" })).toEqual({ value: "new" });
  });

  test("treats blank, null and absent as the same no value", () => {
    expect(toMergePatch({ name: "a" }, { name: "a", description: "" })).toEqual({});
    expect(hasChanged(undefined, "")).toBe(false);
    expect(hasChanged(null, "x")).toBe(true);
  });
});
