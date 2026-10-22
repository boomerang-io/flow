import { redirect } from "react-router-dom";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowFeatures, FlowUser } from "Types";
import { hasPermission } from "Utils/permissionHelper";
import { manageTabs } from "./manageTabs";

/**
 * "/admin" resolved server-side: the caller lands on the first tab their grants and the feature
 * flags allow, with no empty first paint. The grants are the same seven the App layout computes
 * for the routes (Features/App/App.tsx); the profile is read again here because a route loader
 * cannot see the root loader's data. A failed read falls through to the page, which renders the
 * usual 403 when it can find no tab.
 */
export async function loader({ request }: { request: Request }) {
  const api = serverFetch(request);
  const [profileResult, featuresResult] = await Promise.allSettled([
    api.get<FlowUser>(serviceUrl.getUserProfile()),
    api.get<FlowFeatures>(serviceUrl.getFeatureFlags()),
  ]);
  if (profileResult.status !== "fulfilled" || featuresResult.status !== "fulfilled") {
    return null;
  }
  const user = profileResult.value.data;
  const flags = featuresResult.value.data.features;
  const first = manageTabs(
    {
      canReadSettings: hasPermission(user, "system", "read"),
      canReadWorkspaces: hasPermission(user, "workspace", "read"),
      canReadUsers: hasPermission(user, "user", "read"),
      canReadParameters: hasPermission(user, "parameter", "read"),
      canReadTokens: hasPermission(user, "token", "read"),
      canReadTasks: hasPermission(user, "task", "read"),
      canReadAudit: hasPermission(user, "system", "read"),
    },
    {
      workspaceManagement: Boolean(flags["workspace.management"]),
      userManagement: Boolean(flags["user.management"]),
      globalParameters: Boolean(flags["global.parameters"]),
      tokens: Boolean(flags["tokens"]),
    },
  ).find((tab) => tab.allowed);
  return first ? redirect(first.to) : null;
}
