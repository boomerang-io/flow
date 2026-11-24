import { isIdShapedName, toWorkspaceName, workspaceNameProblem } from "./workspaceName";

// The server's rule, case by case: what the webapp shows is exactly what the server accepts.
describe("toWorkspaceName", () => {
  test.each([
    ["Platform Team", "platform-team"],
    ["team2", "team2"],
    ["Team 2", "team-2"],
    ["O'Brien's Crew", "o-brien-s-crew"],
    ["Ops & Infra!", "ops-infra"],
    ["  spaced   out  ", "-spaced-out-"],
    ["Café Crew", "caf-crew"],
    ["开发团队 Dev", "-dev"],
    ["开发团队", ""],
    ["a--b__c", "a-bc"],
  ])("%s becomes %s", (displayName, name) => {
    expect(toWorkspaceName(displayName)).toBe(name);
  });
});

describe("workspaceNameProblem", () => {
  test("a display name with nothing for the web address says so", () => {
    expect(workspaceNameProblem("开发团队")).toMatch(/nothing for the web address/);
    expect(workspaceNameProblem("!!!")).toMatch(/nothing for the web address/);
    expect(workspaceNameProblem("--")).toMatch(/nothing for the web address/);
  });

  test("a name shaped like an id is refused", () => {
    expect(isIdShapedName("64b8d5a5040e205ee3383ab1")).toBe(true);
    expect(workspaceNameProblem("64b8d5a5040e205ee3383ab1")).toMatch(/workspace id/);
  });

  test("an ordinary name has no problem", () => {
    expect(workspaceNameProblem("Platform Team")).toBeNull();
    expect(workspaceNameProblem("Dev 开发")).toBeNull();
  });
});
