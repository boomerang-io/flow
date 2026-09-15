// Look for the data injected into the HTML file from the Express app
// See server/app.js for implementation
import queryString, { StringifyOptions } from "query-string";
import { generatePath } from "react-router-dom";
import { Envs } from "Constants";

// Framework mode's ssr:false build still runs this module once in Node (to prerender
// server/build/index.html's root shell - see react-router.config.ts) before it ever runs in a
// browser, so the `window` read below needs the same guard any SSR-reachable module needs -
// `window` itself doesn't exist yet at that point. Runtime behaviour in the browser (including
// the window._SERVER_DATA override) is unchanged.
export const APP_ROOT =
  typeof window !== "undefined" && window._SERVER_DATA && window._SERVER_DATA.APP_ROOT
    ? window._SERVER_DATA.APP_ROOT
    : "/apps/flow";

export const CORE_ENV_URL =
  typeof window !== "undefined" && window._SERVER_DATA && window._SERVER_DATA.CORE_ENV_URL
    ? window._SERVER_DATA.CORE_ENV_URL
    : "";

export const BASE_DOCUMENTATION_URL = "https://www.useboomerang.io/docs/boomerang-flow";

//@ts-ignore
export const isDevEnv = import.meta.env.MODE === Envs.Dev;
//@ts-ignore
export const isTestEnv = import.meta.env.MODE === Envs.Test;

type AppPathKey =
  | "Root"
  | "Error"
  | "Activity"
  | "Actions"
  | "ActionsApprovals"
  | "ActionsManual"
  | "Callback"
  | "Editor"
  | "EditorCanvas"
  | "EditorConfigure"
  | "EditorConfigureGeneral"
  | "EditorConfigureTriggers"
  | "EditorConfigureRun"
  | "EditorConfigureParams"
  | "EditorConfigureWorkspaces"
  | "EditorConfigureTokens"
  | "EditorChangelog"
  | "EditorProperties"
  | "EditorSchedule"
  | "Home"
  | "Profile"
  | "Insights"
  | "Integrations"
  | "ManageTasks"
  | "ManageTasksDetail"
  | "ManageTasksEditor"
  | "ManageWorkspace"
  | "Properties"
  | "Schedules"
  | "Settings"
  | "TemplateWorkflows"
  | "Tasks"
  | "TasksDetail"
  | "TasksEditor"
  | "ManageWorkspace"
  | "ManageWorkspaceSettings"
  | "ManageWorkspaceWorkflows"
  | "ManageWorkspaceLabels"
  | "ManageWorkspaceQuotas"
  | "ManageWorkspaceApprovers"
  | "ManageWorkspaceParameters"
  | "ManageWorkspaceTokens"
  | "Run"
  | "Tokens"
  | "WorkspaceList"
  | "User"
  | "UserList"
  | "UserLabels"
  | "UserSettings"
  | "Workflows";

export const AppPath: Record<AppPathKey, string> = {
  Root: "/",
  Error: "/error",
  Activity: "/:workspace/activity",
  Run: "/:workspace/activity/:runId",
  Actions: "/:workspace/actions",
  ActionsApprovals: "/:workspace/actions/approvals",
  ActionsManual: "/:workspace/actions/manual",
  Callback: "/callback",
  Editor: "/:workspace/editor/:workflow",
  EditorCanvas: `/:workspace/editor/:workflow/canvas`,
  EditorConfigure: `/:workspace/editor/:workflow/configure`,
  EditorConfigureGeneral: `/:workspace/editor/:workflow/configure/general`,
  EditorConfigureTriggers: `/:workspace/editor/:workflow/configure/triggers`,
  EditorConfigureRun: `/:workspace/editor/:workflow/configure/run`,
  EditorConfigureParams: `/:workspace/editor/:workflow/configure/parameters`,
  EditorConfigureWorkspaces: `/:workspace/editor/:workflow/configure/workspaces`,
  EditorConfigureTokens: `/:workspace/editor/:workflow/configure/tokens`,
  EditorChangelog: `/:workspace/editor/:workflow/changelog`,
  EditorProperties: `/:workspace/editor/:workflow/parameters`,
  EditorSchedule: `/:workspace/editor/:workflow/schedule`,
  Home: "/home",
  Profile: "/profile",
  Insights: "/:workspace/insights",
  Integrations: "/:workspace/integrations",
  Workflows: "/:workspace/workflows",
  Schedules: "/:workspace/schedules",

  //Manage
  ManageTasks: `/:workspace/task-manager`,
  ManageTasksDetail: `/:workspace/task-manager/:name/:version`,
  ManageTasksEditor: `/:workspace/task-manager/:name/:version/editor`,
  ManageWorkspaceParameters: `/:workspace/parameters`,
  ManageWorkspace: `/:workspace/manage`,
  ManageWorkspaceTokens: "/:workspace/manage/tokens",
  ManageWorkspaceSettings: "/:workspace/manage/settings",
  ManageWorkspaceWorkflows: "/:workspace/manage/workflows",
  ManageWorkspaceQuotas: "/:workspace/manage/quotas",
  ManageWorkspaceLabels: "/:workspace/manage/labels",
  ManageWorkspaceApprovers: `/:workspace/manage/approver-groups`,

  //admin
  Properties: "/admin/parameters",
  Settings: "/admin/settings",
  TemplateWorkflows: "/admin/template-workflows",
  Tasks: "/admin/task-manager",
  TasksDetail: `/admin/task-manager/:name/:version`,
  TasksEditor: `/admin/task-manager/:name/:version/editor`,
  WorkspaceList: "/admin/workspaces",
  Tokens: "/admin/tokens",
  User: "/admin/users/:userId",
  UserLabels: "/admin/users/:userId/labels",
  UserSettings: "/admin/users/:userId/settings",
  UserList: "/admin/users",
};

interface WorkflowArg {
  workflow: string;
}

interface WorkspaceArg {
  workspace: string;
}

interface UserIdArg {
  userId: string;
}

type WorkspaceRouteArgs = WorkflowArg & WorkspaceArg;
interface ManageTaskTemplateArgs {
  workspace: string;
  name: string;
  version: string;
}
interface AdminTaskTemplateArgs {
  name: string;
  version: string;
}
interface ExecutionArgs {
  runId: string;
}

/**
 * Query-string options shared by every page that reads or writes its filters through `query-string`,
 * and by the two `appLink` builders below that carry a query.
 */

/**
 * Query-string options shared by every page that reads or writes its filters through `query-string`,
 * and by the two `appLink` builders below that carry a query.
 */
export const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };

/**
 * `appLink` is derived from `AppPath` - the single table of route patterns above - so a route is
 * written down exactly once. `generatePath` fills the `:params` in and URL-encodes each value, and
 * the two builders that carry a query string stringify it with `queryStringOptions` rather than
 * interpolating raw values into the URL.
 */
export const appLink = {
  activity: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Activity, { workspace }),
  actions: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Actions, { workspace }),
  actionsApprovals: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ActionsApprovals, { workspace }),
  actionsManual: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ActionsManual, { workspace }),
  editorCanvas: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorCanvas, { workspace, workflow }),
  editorConfigure: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigure, { workspace, workflow }),
  editorConfigureGeneral: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureGeneral, { workspace, workflow }),
  editorConfigureTriggers: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureTriggers, { workspace, workflow }),
  editorConfigureRun: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureRun, { workspace, workflow }),
  editorConfigureParams: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureParams, { workspace, workflow }),
  editorConfigureWorkspaces: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureWorkspaces, { workspace, workflow }),
  editorConfigureTokens: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorConfigureTokens, { workspace, workflow }),
  editorChangelog: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorChangelog, { workspace, workflow }),
  editorProperties: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorProperties, { workspace, workflow }),
  editorSchedule: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    generatePath(AppPath.EditorSchedule, { workspace, workflow }),
  execution: ({ workspace, runId }: WorkspaceArg & ExecutionArgs) => generatePath(AppPath.Run, { workspace, runId }),
  home: () => AppPath.Home,
  profile: () => AppPath.Profile,
  insights: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Insights, { workspace }),
  integrations: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Integrations, { workspace }),
  manageTasks: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageTasks, { workspace }),
  manageTasksEdit: ({ workspace, name, version }: ManageTaskTemplateArgs) =>
    generatePath(AppPath.ManageTasksDetail, { workspace, name, version }),
  manageTasksYaml: ({ workspace, name, version }: ManageTaskTemplateArgs) =>
    generatePath(AppPath.ManageTasksEditor, { workspace, name, version }),
  manageWorkspace: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageWorkspace, { workspace }),
  manageWorkspaceApprovers: ({ workspace }: WorkspaceArg) =>
    generatePath(AppPath.ManageWorkspaceApprovers, { workspace }),
  manageWorkspaceWorkflows: ({ workspace }: WorkspaceArg) =>
    generatePath(AppPath.ManageWorkspaceWorkflows, { workspace }),
  manageWorkspaceLabels: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageWorkspaceLabels, { workspace }),
  manageWorkspaceQuotas: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageWorkspaceQuotas, { workspace }),
  manageWorkspaceSettings: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageWorkspaceSettings, { workspace }),
  manageWorkspaceTokens: ({ workspace }: WorkspaceArg) => generatePath(AppPath.ManageWorkspaceTokens, { workspace }),
  manageWorkspaceParameters: ({ workspace }: WorkspaceArg) =>
    generatePath(AppPath.ManageWorkspaceParameters, { workspace }),
  manageUsers: () => AppPath.UserList,
  properties: () => AppPath.Properties,
  schedules: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Schedules, { workspace }),
  // Deep-links into the schedules page's existing "workflows" FilterableMultiSelect (matched
  // against Workflow.name - see Features/Schedules/Schedules.tsx's selectedWorkflowRefs) rather
  // than a new per-schedule focus mechanism, which the page does not have.
  schedulesForWorkflow: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    `${generatePath(AppPath.Schedules, { workspace })}?${queryString.stringify({ workflows: workflow }, queryStringOptions)}`,
  settings: () => AppPath.Settings,
  templateWorkflows: () => AppPath.TemplateWorkflows,
  adminTasks: () => AppPath.Tasks,
  adminTasksDetail: ({ name, version }: AdminTaskTemplateArgs) => generatePath(AppPath.TasksDetail, { name, version }),
  adminTasksEditor: ({ name, version }: AdminTaskTemplateArgs) => generatePath(AppPath.TasksEditor, { name, version }),
  workspaceList: () => AppPath.WorkspaceList,
  tokens: () => AppPath.Tokens,
  user: ({ userId }: UserIdArg) => generatePath(AppPath.User, { userId }),
  userLabels: ({ userId }: UserIdArg) => generatePath(AppPath.UserLabels, { userId }),
  userSettings: ({ userId }: UserIdArg) => generatePath(AppPath.UserSettings, { userId }),
  userList: () => AppPath.UserList,
  workflows: ({ workspace }: WorkspaceArg) => generatePath(AppPath.Workflows, { workspace }),
  workflowActivity: ({ workspace, workflow }: WorkspaceRouteArgs) =>
    `${generatePath(AppPath.Activity, { workspace })}?${queryString.stringify({ page: 0, size: 10, workflows: workflow }, queryStringOptions)}`,
  //external apps
  docsWorkflowEditor: () => `${BASE_DOCUMENTATION_URL}/fundamentals/triggers`,
};

/**
 * new Feature Flags
 */
export enum FeatureFlag {
  ActivityEnabled = "ActivityEnabled",
  EditVerifiedTasksEnabled = "EditVerifiedTasksEnabled",
  GlobalParametersEnabled = "GlobalParametersEnabled",
  InsightsEnabled = "InsightsEnabled",
  WorkspaceManagementEnabled = "WorkspaceManagementEnabled",
  WorkspaceParametersEnabled = "WorkspaceParametersEnabled",
  WorkspaceTasksEnabled = "WorkspaceTasksEnabled",
  UserManagementEnabled = "UserManagementEnabled",
  WorkspaceQuotasEnabled = "WorkspaceQuotasEnabled",
  WorkflowTokensEnabled = "WorkflowTokensEnabled",
  WorkflowTriggersEnabled = "WorkflowTriggersEnabled",
}

