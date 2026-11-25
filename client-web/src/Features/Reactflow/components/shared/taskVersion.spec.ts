import { splitTaskVersionValues, unsetParamsNotice, versionTagText } from "./taskVersion";

describe("task version helpers", () => {
  it("tags a Latest node with the version it runs and a pinned one with its own", () => {
    expect(versionTagText("latest", 5)).toBe("Latest (v5)");
    expect(versionTagText(4, 5)).toBe("v4");
    // A node with no version yet is pinned to the latest when the workflow is saved.
    expect(versionTagText(undefined, 5)).toBe("v5");
  });

  it("takes the version off the form's values: unchanged, Latest, or a pin", () => {
    expect(splitTaskVersionValues({ prompt: "x" })).toEqual({
      changed: false,
      taskVersion: undefined,
      rest: { prompt: "x" },
    });
    expect(splitTaskVersionValues({ taskVersion: "latest", prompt: "x" })).toEqual({
      changed: true,
      taskVersion: "latest",
      rest: { prompt: "x" },
    });
    expect(splitTaskVersionValues({ taskVersion: "4" })).toEqual({ changed: true, taskVersion: 4, rest: {} });
  });

  it("lists the fields the node doesn't set in plain words", () => {
    expect(unsetParamsNotice(5, ["Registry"])).toBe("v5 has fields this node doesn't set: Registry.");
    expect(unsetParamsNotice(5, ["Registry", "Token"])).toBe("v5 has fields this node doesn't set: Registry, Token.");
  });
});
