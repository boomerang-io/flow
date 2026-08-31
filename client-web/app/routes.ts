import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

// Routes config API (explicit path -> file entries), not file-based conventions - the route
// tree previously lived as JSX in src/Features/App/AppRoutes.tsx (see that file for the
// permission/outlet-context plumbing every leaf route file below reads via Protected /
// useRoutePermissions). Framework mode needs one real file per route (its `file` field always
// points at a module, never an inline element), so each entry below is a thin file under
// app/routes/ that composes the existing, untouched Features/* component - the same
// lazy-loading grouping AppRoutes.tsx used to do inline, just split one file per route as the
// route-file API requires. None of the 55 files under src/Features/ are touched or moved.
//
// Paths are inlined rather than imported from Config/appConfig's AppPath: this file is loaded
// by React Router's own config loader (a separate, minimal vite-node pass that resolves the
// project's real vite.config.mts to build the app), and that pass runs before the config it's
// building - including this file's own resolve.alias entries - exists to resolve against, so a
// bare-alias import chain (appConfig.ts itself imports "Constants") fails to resolve there. Keep
// these values in sync with the AppPath entries of the same name in src/Config/appConfig.ts.
export default [
  // Sign-in plumbing lives OUTSIDE the App layout: these routes must render without the
  // bootstrap (the callback lands here precisely because there is no session yet), and the
  // callback must never auto-trigger sign-in - see Features/Auth. Static two-segment paths, so
  // they outrank the layout's "/:workspace/*" catch-all regardless of ordering.
  route("/auth/signin", "routes/authSignin.tsx"),
  route("/auth/callback", "routes/authCallback.tsx"),
  route("/auth/logout", "routes/authLogout.tsx"),
  // BFF resource routes (loader/action-only modules, no UI - see Config/resourceRoutes.ts):
  // same-origin endpoints the browser calls where a route loader/action cannot fit (the Yup
  // async name-availability probes, TaskUpdateModal's on-demand read, LazyLog's log stream,
  // the pre-bootstrap activation submit); their server-side code calls service-core via
  // Config/serverFetch. Outside the App layout on purpose: none of them wants the root
  // bootstrap, and /res/activate must work while that bootstrap reports activationRequired.
  // The static "/res" first segment outranks "/:workspace/*" under route ranking (a workspace
  // slug literally named "res" is shadowed - same accepted class as "auth"/"admin" above).
  route("/res/workspace/validate-name", "routes/resWorkspaceValidateName.tsx"),
  route("/res/task/:name", "routes/resTask.tsx"),
  route("/res/activate", "routes/resActivate.tsx"),
  route("/res/taskrun/:id/log", "routes/resTaskrunLog.tsx"),
  layout("../src/Features/App/index.tsx", [
    route("/home", "routes/home.tsx"),
    route("/profile", "routes/profile.tsx"),
    route("/admin/settings", "routes/settings.tsx"),
    route("/admin/parameters", "routes/globalParameters.tsx"),
    route("/admin/template-workflows", "routes/templateWorkflows.tsx"),
    route("/admin/task-manager/*", "routes/adminTasks.tsx"),
    route("/admin/tokens", "routes/tokens.tsx"),
    route("/admin/workspaces", "routes/workspaceList.tsx"),
    route("/admin/users", "routes/userList.tsx"),
    route("/admin/users/:userId/*", "routes/userDetailed.tsx"),
    route("/:workspace/activity/:runId", "routes/run.tsx"),
    route("/:workspace/activity", "routes/activity.tsx"),
    route("/:workspace/insights", "routes/insights.tsx"),
    route("/:workspace/parameters", "routes/workspaceParameters.tsx"),
    // The Manage Workspace tabs are real nested routes, not an inner <Routes> switch inside one
    // "/*" splat route (which is what routes/manageWorkspace.tsx used to render): the parent is a
    // layout route whose loader fetches the workspace record the header and every tab reads, and
    // each tab below owns the loader/action for its own data. Same URLs as before -
    // AppPath.ManageWorkspace* / appLink.manageWorkspace* are unchanged, and Members stays the
    // index route at bare `/:workspace/manage`.
    route("/:workspace/manage", "routes/manageWorkspace.tsx", [
      index("routes/manageWorkspaceMembers.tsx"),
      route("workflows", "routes/manageWorkspaceWorkflows.tsx"),
      route("approver-groups", "routes/manageWorkspaceApproverGroups.tsx"),
      route("quotas", "routes/manageWorkspaceQuotas.tsx"),
      route("tokens", "routes/manageWorkspaceTokens.tsx"),
      route("settings", "routes/manageWorkspaceSettings.tsx"),
    ]),
    route("/:workspace/actions/*", "routes/actions.tsx"),
    route("/:workspace/editor/:workflow/*", "routes/editor.tsx"),
    route("/:workspace/schedules", "routes/schedules.tsx"),
    route("/:workspace/workflows", "routes/workflows.tsx"),
    route("/:workspace/integrations", "routes/integrations.tsx"),
    route("/:workspace/task-manager/*", "routes/manageTasks.tsx"),
    route("/", "routes/rootRedirect.tsx"),
    // Any other single-segment path is treated as a workspace slug (matching the
    // pre-migration behaviour); deeper unmatched sub-paths 404 inside that workspace.
    route("/:workspace/*", "routes/workspaceCatchAll.tsx"),
  ]),
] satisfies RouteConfig;
