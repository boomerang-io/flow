// Look for the data injected into the HTML file from the Express app
// See server/app.js for implementation
import { StringifyOptions } from "query-string";
import { Envs } from "Constants";

export const APP_ROOT =
  window._SERVER_DATA && window._SERVER_DATA.APP_ROOT ? window._SERVER_DATA.APP_ROOT : "/apps/flow";

export const CORE_ENV_URL =
  window._SERVER_DATA && window._SERVER_DATA.CORE_ENV_URL ? window._SERVER_DATA.CORE_ENV_URL : "";

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

export const appLink = {
  activity: ({ workspace }: WorkspaceArg) => `/${workspace}/activity`,
  actions: ({ workspace }: WorkspaceArg) => `/${workspace}/actions`,
  actionsApprovals: ({ workspace }: WorkspaceArg) => `/${workspace}/actions/approvals`,
  actionsManual: ({ workspace }: WorkspaceArg) => `/${workspace}/actions/manual`,
  editorCanvas: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/canvas`,
  editorConfigure: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure`,
  editorConfigureGeneral: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/general`,
  editorConfigureTriggers: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/triggers`,
  editorConfigureRun: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/run`,
  editorConfigureParams: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/parameters`,
  editorConfigureWorkspaces: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/workspaces`,
  editorConfigureTokens: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/configure/tokens`,
  editorChangelog: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/changelog`,
  editorProperties: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/parameters`,
  editorSchedule: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/editor/${workflow}/schedule`,
  execution: ({ workspace, runId }: WorkspaceArg & ExecutionArgs) => `/${workspace}/activity/${runId}`,
  home: () => "/home",
  profile: () => "/profile",
  insights: ({ workspace }: WorkspaceArg) => `/${workspace}/insights`,
  integrations: ({ workspace }: WorkspaceArg) => `/${workspace}/integrations`,
  manageTasks: ({ workspace }: WorkspaceArg) => `/${workspace}/task-manager`,
  manageTasksEdit: ({ workspace, name, version }: ManageTaskTemplateArgs) => `/${workspace}/task-manager/${name}/${version}`,
  manageTasksYaml: ({ workspace, name, version }: ManageTaskTemplateArgs) =>
    `/${workspace}/task-manager/${name}/${version}/editor`,
  manageWorkspace: ({ workspace }: WorkspaceArg) => `/${workspace}/manage`,
  manageWorkspaceApprovers: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/approver-groups`,
  manageWorkspaceWorkflows: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/workflows`,
  manageWorkspaceLabels: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/labels`,
  manageWorkspaceQuotas: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/quotas`,
  manageWorkspaceSettings: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/settings`,
  manageWorkspaceTokens: ({ workspace }: WorkspaceArg) => `/${workspace}/manage/tokens`,
  manageWorkspaceParameters: ({ workspace }: WorkspaceArg) => `/${workspace}/parameters`,
  manageUsers: () => "/admin/users",
  properties: () => "/admin/parameters",
  schedule: () => "/schedule",
  settings: () => "/admin/settings",
  templateWorkflows: () => "/admin/template-workflows",
  adminTasks: () => "/admin/task-manager",
  adminTasksDetail: ({ name, version }: AdminTaskTemplateArgs) => `/admin/task-manager/${name}/${version}`,
  adminTasksEditor: ({ name, version }: AdminTaskTemplateArgs) => `/admin/task-manager/${name}/${version}/editor`,
  workspaceList: () => "/admin/workspaces",
  tokens: () => `/admin/tokens`,
  user: ({ userId }: UserIdArg) => `/admin/users/${userId}`,
  userLabels: ({ userId }: UserIdArg) => `/admin/users/${userId}/labels`,
  userSettings: ({ userId }: UserIdArg) => `/admin/users/${userId}/settings`,
  userList: () => "/admin/users",
  workflows: ({ workspace }: WorkspaceArg) => `/${workspace}/workflows`,
  workflowActivity: ({ workspace, workflow }: WorkspaceRouteArgs) => `/${workspace}/activity?page=0&size=10&workflows=${workflow}`,
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

export const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };
