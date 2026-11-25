import { missingParamsWarning, splitTaskVersionValues, versionTagText } from "./taskVersion";

describe("task version helpers", () => {
  it("tags a node with no version as Latest and a pinned one with its own", () => {
    expect(versionTagText(undefined, 5)).toBe("Latest (v5)");
    expect(versionTagText(null, 5)).toBe("Latest (v5)");
    expect(versionTagText(4, 5)).toBe("v4");
  });

  it("takes the version off the form's values: unchanged, Latest, or a pin", () => {
    expect(splitTaskVersionValues({ prompt: "x" })).toEqual({
      changed: false,
      taskVersion: null,
      rest: { prompt: "x" },
    });
    expect(splitTaskVersionValues({ taskVersion: "latest", prompt: "x" })).toEqual({
      changed: true,
      taskVersion: null,
      rest: { prompt: "x" },
    });
    expect(splitTaskVersionValues({ taskVersion: "4" })).toEqual({ changed: true, taskVersion: 4, rest: {} });
  });

  it("names each missing param in plain words", () => {
    expect(missingParamsWarning(5, ["Registry"])).toBe(
      "v5 needs Registry, and this node doesn't set it yet. Fill it in on Parameters before you apply.",
    );
    expect(missingParamsWarning(5, ["Registry", "Token", "Region"])).toBe(
      "v5 needs Registry, Token and Region, and this node doesn't set them yet. Fill them in on Parameters before you apply.",
    );
  });
});
