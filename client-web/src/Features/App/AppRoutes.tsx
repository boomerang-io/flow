import { lazy } from "react";
import { Navigate, Route, useOutletContext } from "react-router-dom";
import { Error404 } from "@boomerang-io/carbon-addons-boomerang-react";
import { AppPath } from "Config/appConfig";
import { ProtectedRoute, WorkspaceContainer } from "./App";

const Activity = lazy(() => import("Features/Activity"));
const Actions = lazy(() => import("Features/Actions"));
const Editor = lazy(() => import("Features/WorkflowEditor"));
const Execution = lazy(() => import("Features/WorkflowRun"));
const Tokens = lazy(() => import("Features/GlobalTokens/GlobalTokens"));
const Insights = lazy(() => import("Features/Insights"));
const Integrations = lazy(() => import("Features/Integrations"));
const Schedules = lazy(() => import("Features/Schedules"));
const Settings = lazy(() => import("Features/Settings"));
const TemplateWorkflows = lazy(() => import("Features/TemplateWorkflows"));
const Workspaces = lazy(() => import("Features/Workspaces"));
const ManageWorkspace = lazy(() => import("Features/WorkspaceDetailed"));
const WorkspaceParameters = lazy(() => import("Features/Parameters/WorkspaceParameters"));
const WorkspaceTasks = lazy(() => import("Features/TaskManager/WorkspaceTasks"));
const AdminTasks = lazy(() => import("Features/TaskManager/AdminTasks"));
const UserList = lazy(() => import("Features/Users"));
const UserProfile = lazy(() => import("Features/UserProfile"));
const Workflows = lazy(() => import("Features/Workflows"));
const Home = lazy(() => import("Features/Home"));

// The permission/feature-flag gates below depend on hooks (useAppContext, useFeature) that only
// resolve inside the App layout route, so App.tsx computes them once and hands them down through
// the layout route's <Outlet context={...} />. Route elements defined here read the one flag
// they need back out via useRoutePermissions() instead of recomputing it.
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
function Protected({ permission, children }: { permission: keyof RoutePermissions; children: React.ReactNode }) {
  const permissions = useRoutePermissions();
  return <ProtectedRoute allowed={permissions[permission]}>{children}</ProtectedRoute>;
}

// The route tree previously lived inside App.tsx's own <Routes>, matched by the router's single
// catch-all entry point - loaders/actions can only attach to routes declared in the router
// config itself, so this tree is now built as the App layout route's children in Root.tsx.
// Structure, nesting, and the ProtectedRoute/WorkspaceContainer gating are unchanged from the
// previous wave; only how `allowed` is sourced (outlet context instead of a local variable)
// is different.
export const appRouteChildren = (
  <>
    <Route path="/home" element={<Home />} />
    <Route path="/profile" element={<UserProfile />} />
    <Route
      path={AppPath.Settings}
      element={
        <Protected permission="canReadSettings">
          <Settings />
        </Protected>
      }
    />
    {/* Route-module style: loader/action/Component all resolve together from one dynamic
    import, so this route stays code-split exactly like the plain lazy() routes around it -
    the only difference is this module also exports `loader`/`action` next to its default
    export, and AppRoutes.tsx wires them up here via `lazy` instead of a static `element`. */}
    <Route
      path={AppPath.Properties}
      lazy={async () => {
        const { default: GlobalParameters, loader, action } = await import(
          "Features/Parameters/GlobalParameters/GlobalParameters"
        );
        return {
          loader,
          action,
          Component: () => (
            <Protected permission="canReadParameters">
              <GlobalParameters />
            </Protected>
          ),
        };
      }}
    />
    <Route
      path={AppPath.TemplateWorkflows}
      element={
        <Protected permission="canReadWorkflowTemplates">
          <TemplateWorkflows />
        </Protected>
      }
    />
    <Route
      path={`${AppPath.Tasks}/*`}
      element={
        <Protected permission="canReadTasks">
          <AdminTasks />
        </Protected>
      }
    />
    <Route
      path={AppPath.Tokens}
      element={
        <Protected permission="canReadTokens">
          <Tokens />
        </Protected>
      }
    />
    <Route
      path={AppPath.WorkspaceList}
      element={
        <Protected permission="canReadWorkspaces">
          <Workspaces />
        </Protected>
      }
    />
    <Route
      path={AppPath.UserList}
      element={
        <Protected permission="canReadUsers">
          <UserList />
        </Protected>
      }
    />
    {/* Route-module style, like AppPath.Properties above - the loader reads the :userId path
    param (see UserDetailed.tsx's loader({ params })). This used to be a nested route matched
    by Users.tsx's own internal <Routes> for ":userId/*"; it's a standalone top-level route now
    so it can carry a loader. */}
    <Route
      path={`${AppPath.User}/*`}
      lazy={async () => {
        const { default: UserDetailed, loader } = await import("Features/UserDetailed/UserDetailed");
        return {
          loader,
          Component: () => (
            <Protected permission="canReadUsers">
              <UserDetailed />
            </Protected>
          ),
        };
      }}
    />
    <Route
      path={AppPath.Run}
      element={
        <WorkspaceContainer>
          <Protected permission="activityEnabled">
            <Execution />
          </Protected>
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.Activity}
      element={
        <WorkspaceContainer>
          <Protected permission="activityEnabled">
            <Activity />
          </Protected>
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.Insights}
      element={
        <WorkspaceContainer>
          <Protected permission="insightsEnabled">
            <Insights />
          </Protected>
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.ManageWorkspaceParameters}
      element={
        <WorkspaceContainer>
          <Protected permission="workspaceParametersEnabled">
            <WorkspaceParameters />
          </Protected>
        </WorkspaceContainer>
      }
    />
    <Route
      path={`${AppPath.ManageWorkspace}/*`}
      element={
        <WorkspaceContainer>
          <ManageWorkspace />
        </WorkspaceContainer>
      }
    />
    <Route
      path={`${AppPath.Actions}/*`}
      element={
        <WorkspaceContainer>
          <Actions />
        </WorkspaceContainer>
      }
    />
    <Route
      path={`${AppPath.Editor}/*`}
      element={
        <WorkspaceContainer>
          <Editor />
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.Schedules}
      element={
        <WorkspaceContainer>
          <Schedules />
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.Workflows}
      element={
        <WorkspaceContainer>
          <Workflows />
        </WorkspaceContainer>
      }
    />
    <Route
      path={AppPath.Integrations}
      element={
        <WorkspaceContainer>
          <Integrations />
        </WorkspaceContainer>
      }
    />
    <Route
      path={`${AppPath.ManageTasks}/*`}
      element={
        <WorkspaceContainer>
          <WorkspaceTasks />
        </WorkspaceContainer>
      }
    />
    <Route path="/" element={<Navigate to="/home" replace />} />
    {/* Any other single-segment path is treated as a workspace slug (matching the
    pre-migration behaviour); deeper unmatched sub-paths 404 inside that workspace. */}
    <Route
      path="/:workspace/*"
      element={
        <WorkspaceContainer>
          <Error404 theme="boomerang" />
        </WorkspaceContainer>
      }
    />
  </>
);
