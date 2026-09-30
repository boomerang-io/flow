import { AppPath } from "Config/appConfig";
import type { RoutePermissions } from "Features/App/AppRoutes";

export interface ManageTab {
  label: string;
  to: string;
  allowed: boolean;
}

export interface ManageFeatures {
  workspaceManagement: boolean;
  userManagement: boolean;
  globalParameters: boolean;
}

type ManageGrants = Pick<
  RoutePermissions,
  "canReadSettings" | "canReadWorkspaces" | "canReadUsers" | "canReadParameters" | "canReadTokens" | "canReadTasks" | "canReadAudit"
>;

/**
 * The Manage tabs in display order, each with whether a caller may see it: the grant the tab's
 * route checks, and the feature flag the server's navigation applies. Pure, so the same rule
 * serves the layout (from the route permissions) and the "/admin" loader (from the profile).
 */
export function manageTabs(grants: ManageGrants, features: ManageFeatures): Array<ManageTab> {
  return [
    { label: "Settings", to: AppPath.Settings, allowed: grants.canReadSettings },
    { label: "Workspaces", to: AppPath.WorkspaceList, allowed: features.workspaceManagement && grants.canReadWorkspaces },
    { label: "Users", to: AppPath.UserList, allowed: features.userManagement && grants.canReadUsers },
    { label: "Parameters", to: AppPath.Properties, allowed: features.globalParameters && grants.canReadParameters },
    { label: "Tokens", to: AppPath.Tokens, allowed: grants.canReadTokens },
    { label: "Tasks", to: AppPath.Tasks, allowed: grants.canReadTasks },
    { label: "Audit", to: AppPath.Audit, allowed: grants.canReadAudit },
  ];
}
