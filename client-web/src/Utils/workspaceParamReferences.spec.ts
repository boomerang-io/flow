import { describe, expect, it } from "vitest";
import type { Task, WorkflowNode } from "Types";
import { missingWorkspaceParams, missingWorkspaceParamsMessage } from "./workspaceParamReferences";

const githubTask = {
  name: "get-github-repository",
  version: 3,
  spec: {
    params: [
      { name: "token", type: "password", default: "$(workspace.params.github-token)" },
      { name: "url", type: "text", default: "$(workspace.params.github-url)" },
      { name: "repoURL", type: "text" },
    ],
  },
} as unknown as Task;
const tasksByName = { [githubTask.name]: [githubTask] };

function node(params: Array<{ name: string; value: any }>, taskVersion = 3): WorkflowNode {
  return {
    id: "Get repository",
    position: { x: 0, y: 0 },
    data: {
      name: "Get repository",
      taskRef: githubTask.name,
      taskVersion,
      upgradesAvailable: false,
      params,
      results: [],
    },
  } as WorkflowNode;
}

describe("missingWorkspaceParams", () => {
  it("names each default reference the workspace does not provide", () => {
    expect(missingWorkspaceParams([node([])], tasksByName, ["params.other", "workspace.params.other"])).toEqual([
      { node: "Get repository", param: "token", name: "github-token" },
      { node: "Get repository", param: "url", name: "github-url" },
    ]);
  });

  it("matches a workspace parameter in any case, as admission does", () => {
    expect(
      missingWorkspaceParams([node([])], tasksByName, ["workspace.params.GitHub-Token", "workspace.params.GITHUB-URL"]),
    ).toEqual([]);
  });

  it("reads a node's own value over the default, a reference anywhere in it and the older team scope", () => {
    const missing = missingWorkspaceParams(
      [
        node([
          { name: "TOKEN", value: "ghp_typed" },
          { name: "url", value: "https://$(TEAM.params.ghe-host)/api/v3" },
        ]),
      ],
      tasksByName,
      [],
    );

    expect(missing).toEqual([{ node: "Get repository", param: "url", name: "ghe-host" }]);
  });

  it("falls back to the default for a password left empty, but not for other params", () => {
    const missing = missingWorkspaceParams(
      [
        node([
          { name: "token", value: "" },
          { name: "url", value: "" },
        ]),
      ],
      tasksByName,
      [],
    );

    expect(missing).toEqual([{ node: "Get repository", param: "token", name: "github-token" }]);
  });

  it("ignores the defaults of a task version the node does not pin", () => {
    expect(missingWorkspaceParams([node([], 2)], tasksByName, [])).toEqual([]);
  });

  it("checks a node whose task the editor does not hold by its own values only", () => {
    const custom = node([{ name: "token", value: "$(workspace.params.vault-token)" }]);
    custom.data.taskRef = "custom-task";

    expect(missingWorkspaceParams([custom], tasksByName, [])).toEqual([
      { node: "Get repository", param: "token", name: "vault-token" },
    ]);
  });
});

describe("missingWorkspaceParamsMessage", () => {
  it("names the first three and counts the rest", () => {
    const missing = ["a", "b", "c", "d"].map((name) => ({ node: "Node", param: "p", name }));

    expect(missingWorkspaceParamsMessage(missing)).toBe(
      '"Node" › p reads $(workspace.params.a); "Node" › p reads $(workspace.params.b); "Node" › p reads ' +
        "$(workspace.params.c); and 1 more. This workspace does not provide them, so a run fails at that task.",
    );
    expect(missingWorkspaceParamsMessage(missing.slice(0, 1))).toBe(
      '"Node" › p reads $(workspace.params.a). This workspace does not provide it, so a run fails at that task.',
    );
  });
});
