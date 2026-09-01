import { hasPermission, actionMatches } from "./permissionHelper";
import { ResolvedPermissions } from "Types";

const globalRead: ResolvedPermissions = { scope: "global", principal: "**", actions: ["workflow/read"] };
const globalWildcardAction: ResolvedPermissions = { scope: "global", principal: "**", actions: ["**"] };
const globalWildcardResource: ResolvedPermissions = { scope: "global", principal: "**", actions: ["**/read"] };
const workspaceWrite: ResolvedPermissions = { scope: "workspace", principal: "team-a", actions: ["workflow/write"] };

describe("hasPermission", () => {
  it("denies when there are no permissions", () => {
    expect(hasPermission({ permissions: [] }, "workflow", "read")).toBe(false);
    expect(hasPermission({ permissions: undefined }, "workflow", "read")).toBe(false);
    expect(hasPermission(null, "workflow", "read")).toBe(false);
    expect(hasPermission(undefined, "workflow", "read")).toBe(false);
  });

  it("matches an exact global grant anywhere, with or without a workspace ref", () => {
    expect(hasPermission({ permissions: [globalRead] }, "workflow", "read")).toBe(true);
    expect(hasPermission({ permissions: [globalRead] }, "workflow", "read", "team-a")).toBe(true);
    expect(hasPermission({ permissions: [globalRead] }, "workflow", "read", "team-b")).toBe(true);
  });

  it("denies a global grant for an action it does not carry", () => {
    expect(hasPermission({ permissions: [globalRead] }, "workflow", "write")).toBe(false);
    expect(hasPermission({ permissions: [globalRead] }, "task", "read")).toBe(false);
  });

  it("honours the '**' action wildcard as an entire entry", () => {
    expect(hasPermission({ permissions: [globalWildcardAction] }, "workflow", "delete")).toBe(true);
    expect(hasPermission({ permissions: [globalWildcardAction] }, "**", "**")).toBe(true);
  });

  it("honours the '**' resource half of an action string", () => {
    expect(hasPermission({ permissions: [globalWildcardResource] }, "workflow", "read")).toBe(true);
    expect(hasPermission({ permissions: [globalWildcardResource] }, "user", "read")).toBe(true);
    expect(hasPermission({ permissions: [globalWildcardResource] }, "workflow", "write")).toBe(false);
  });

  it("matches a workspace grant only for its own principal", () => {
    expect(hasPermission({ permissions: [workspaceWrite] }, "workflow", "write", "team-a")).toBe(true);
    expect(hasPermission({ permissions: [workspaceWrite] }, "workflow", "write", "team-b")).toBe(false);
    expect(hasPermission({ permissions: [workspaceWrite] }, "workflow", "write")).toBe(false);
  });

  it("checks across multiple grants", () => {
    const permissions = [workspaceWrite, globalRead];
    expect(hasPermission({ permissions }, "workflow", "read")).toBe(true);
    expect(hasPermission({ permissions }, "workflow", "write", "team-a")).toBe(true);
    expect(hasPermission({ permissions }, "workflow", "write", "team-b")).toBe(false);
  });
});

// These cases mirror what PermissionSelector.tsx's buildGrid used to check inline
// (permissions.includes(`${resource}/${action}`) || `${resource}/**` || `**/${action}` ||
// "**/**") before it switched to this shared helper - see PermissionSelector.spec.tsx for the
// grid-building test built on the same fixtures.
describe("actionMatches", () => {
  it("matches an exact 'resource/action' entry", () => {
    expect(actionMatches("workflow/read", "workflow", "read")).toBe(true);
    expect(actionMatches("workflow/read", "workflow", "write")).toBe(false);
    expect(actionMatches("workflow/read", "task", "read")).toBe(false);
  });

  it("matches a 'resource/**' entry for any action on that resource", () => {
    expect(actionMatches("workflow/**", "workflow", "read")).toBe(true);
    expect(actionMatches("workflow/**", "workflow", "delete")).toBe(true);
    expect(actionMatches("workflow/**", "task", "read")).toBe(false);
  });

  it("matches a '**/action' entry for that action on any resource", () => {
    expect(actionMatches("**/read", "workflow", "read")).toBe(true);
    expect(actionMatches("**/read", "task", "read")).toBe(true);
    expect(actionMatches("**/read", "workflow", "write")).toBe(false);
  });

  it("matches '**/**' for any resource/action pair", () => {
    expect(actionMatches("**/**", "workflow", "read")).toBe(true);
    expect(actionMatches("**/**", "user", "delete")).toBe(true);
  });

  it("matches the bare '**' entry for any resource/action pair", () => {
    expect(actionMatches("**", "workflow", "read")).toBe(true);
  });

  it("denies an unrelated entry", () => {
    expect(actionMatches("task/read", "workflow", "read")).toBe(false);
  });
});
