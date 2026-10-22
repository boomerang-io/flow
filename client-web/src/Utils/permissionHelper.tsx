import { FlowUser, ResolvedPermissions } from "Types";

const WILDCARD = "**";

/**
 * True when a user's resolved grants cover resource/action, optionally scoped to a workspace.
 * A "global" grant matches regardless of workspaceRef; a "workspace" grant matches only when
 * workspaceRef equals its principal. Within a grant, an action entry of "**" matches anything,
 * and a "**" resource half (e.g. "**" + "/read") matches any resource for that verb.
 */
export function hasPermission(
  user: Pick<FlowUser, "permissions"> | null | undefined,
  resource: string,
  action: string,
  workspaceRef?: string,
): boolean {
  const permissions = user?.permissions;
  if (!permissions || permissions.length === 0) {
    return false;
  }

  return permissions.some((grant) => grantApplies(grant, workspaceRef) && grantAllows(grant, resource, action));
}

function grantApplies(grant: ResolvedPermissions, workspaceRef?: string): boolean {
  if (grant.scope === "global") {
    return true;
  }
  return Boolean(workspaceRef) && grant.principal === workspaceRef;
}

function grantAllows(grant: ResolvedPermissions, resource: string, action: string): boolean {
  return grant.actions.some((entry) => actionMatches(entry, resource, action));
}

/**
 * True when a single permission entry ("resource/action", "resource/**", "**\/action", "**\/**",
 * or the bare "**") covers resource/action. Exported so PermissionSelector's catalog grid (a flat
 * string[] of granted "resource/action" pairs, not a ResolvedPermissions grant) can share the
 * same "**" wildcard rules instead of re-implementing them - see PermissionSelector.spec.tsx.
 */
export function actionMatches(entry: string, resource: string, action: string): boolean {
  if (entry === WILDCARD) {
    return true;
  }

  const [entryResource, entryAction] = entry.split("/");
  const resourceMatches = entryResource === WILDCARD || entryResource === resource;
  const verbMatches = entryAction === WILDCARD || entryAction === action;
  return resourceMatches && verbMatches;
}
