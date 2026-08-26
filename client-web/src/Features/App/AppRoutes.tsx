import { useOutletContext } from "react-router-dom";
import { ProtectedRoute } from "./App";

// The permission/feature-flag gates below depend on hooks (useAppContext, useFeature) that only
// resolve inside the App layout route, so App.tsx computes them once and hands them down through
// the layout route's <Outlet context={...} />. Route elements defined in app/routes/* read the
// one flag they need back out via useRoutePermissions() instead of recomputing it.
export type RoutePermissions = {
  canReadSettings: boolean;
  canReadParameters: boolean;
  canReadWorkflowTemplates: boolean;
  canReadTasks: boolean;
  canReadTokens: boolean;
  canReadWorkspaces: boolean;
  canReadUsers: boolean;
  activityEnabled: boolean;
  insightsEnabled: boolean;
  workspaceParametersEnabled: boolean;
};

export function useRoutePermissions() {
  return useOutletContext<RoutePermissions>();
}

// Thin adapter so route elements keep using the same ProtectedRoute gating as before ("render
// the guarded element when allowed, otherwise Error403"), sourcing `allowed` from the outlet
// context instead of a prop computed locally in the same component that rendered the routes.
//
// The route tree itself (previously built here as JSX children for Root.tsx's
// createBrowserRouter) now lives in app/routes.ts, using the routes config API - each entry
// there points at a small file under app/routes/ that imports the same Features/* component and
// wraps it with this Protected helper exactly as this file used to inline. Structure, nesting,
// and gating are unchanged from the previous wave; only where the route registration lives is
// different.
export function Protected({ permission, children }: { permission: keyof RoutePermissions; children: React.ReactNode }) {
  const permissions = useRoutePermissions();
  return <ProtectedRoute allowed={permissions[permission]}>{children}</ProtectedRoute>;
}
