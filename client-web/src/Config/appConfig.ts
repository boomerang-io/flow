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
  | "ManageTeam"
  | "Properties"
  | "Schedules"
  | "Settings"
  | "TemplateWorkflows"
  | "Tasks"
  | "TasksDetail"
  | "TasksEditor"
  | "ManageTeam"
  | "ManageTeamSettings"
  | "ManageTeamWorkflows"
  | "ManageTeamLabels"
  | "ManageTeamQuotas"
  | "ManageTeamApprovers"
  | "ManageTeamParameters"
  | "ManageTeamTokens"
  | "Run"
  | "Tokens"
  | "TeamList"
  | "User"
  | "UserList"
  | "UserLabels"
  | "UserSettings"
  | "Workflows";

export const AppPath: Record<AppPathKey, string> = {
  Root: "/",
  Error: "/error",
  Activity: "/:team/activity",
  Run: "/:team/activity/:runId",
  Actions: "/:team/actions",
  ActionsApprovals: "/:team/actions/approvals",
  ActionsManual: "/:team/actions/manual",
  Callback: "/callback",
  Editor: "/:team/editor/:workflow",
  EditorCanvas: `/:team/editor/:workflow/canvas`,
  EditorConfigure: `/:team/editor/:workflow/configure`,
  EditorConfigureGeneral: `/:team/editor/:workflow/configure/general`,
  EditorConfigureTriggers: `/:team/editor/:workflow/configure/triggers`,
  EditorConfigureRun: `/:team/editor/:workflow/configure/run`,
  EditorConfigureParams: `/:team/editor/:workflow/configure/parameters`,
  EditorConfigureWorkspaces: `/:team/editor/:workflow/configure/workspaces`,
  EditorConfigureTokens: `/:team/editor/:workflow/configure/tokens`,
  EditorChangelog: `/:team/editor/:workflow/changelog`,
  EditorProperties: `/:team/editor/:workflow/parameters`,
  EditorSchedule: `/:team/editor/:workflow/schedule`,
  Home: "/home",
  Profile: "/profile",
  Insights: "/:team/insights",
  Integrations: "/:team/integrations",
  Workflows: "/:team/workflows",
  Schedules: "/:team/schedules",

  //Manage
  ManageTasks: `/:team/task-manager`,
  ManageTasksDetail: `/:team/task-manager/:name/:version`,
  ManageTasksEditor: `/:team/task-manager/:name/:version/editor`,
  ManageTeamParameters: `/:team/parameters`,
  ManageTeam: `/:team/manage`,
  ManageTeamTokens: "/:team/manage/tokens",
  ManageTeamSettings: "/:team/manage/settings",
  ManageTeamWorkflows: "/:team/manage/workflows",
  ManageTeamQuotas: "/:team/manage/quotas",
  ManageTeamLabels: "/:team/manage/labels",
  ManageTeamApprovers: `/:team/manage/approver-groups`,

  //admin
  Properties: "/admin/parameters",
  Settings: "/admin/settings",
  TemplateWorkflows: "/admin/template-workflows",
  Tasks: "/admin/task-manager",
  TasksDetail: `/admin/task-manager/:name/:version`,
  TasksEditor: `/admin/task-manager/:name/:version/editor`,
  TeamList: "/admin/teams",
  Tokens: "/admin/tokens",
  User: "/admin/users/:userId",
  UserLabels: "/admin/users/:userId/labels",
  UserSettings: "/admin/users/:userId/settings",
  UserList: "/admin/users",
};

interface WorkflowArg {
  workflow: string;
}

interface TeamArg {
  team: string;
}

interface UserIdArg {
  userId: string;
}

type TeamRouteArgs = WorkflowArg & TeamArg;
interface ManageTaskTemplateArgs {
  team: string;
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
  activity: ({ team }: TeamArg) => `/${team}/activity`,
  actions: ({ team }: TeamArg) => `/${team}/actions`,
  actionsApprovals: ({ team }: TeamArg) => `/${team}/actions/approvals`,
  actionsManual: ({ team }: TeamArg) => `/${team}/actions/manual`,
  editorCanvas: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/canvas`,
  editorConfigure: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure`,
  editorConfigureGeneral: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/general`,
  editorConfigureTriggers: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/triggers`,
  editorConfigureRun: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/run`,
  editorConfigureParams: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/parameters`,
  editorConfigureWorkspaces: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/workspaces`,
  editorConfigureTokens: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/configure/tokens`,
  editorChangelog: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/changelog`,
  editorProperties: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/parameters`,
  editorSchedule: ({ team, workflow }: TeamRouteArgs) => `/${team}/editor/${workflow}/schedule`,
  execution: ({ team, runId }: TeamArg & ExecutionArgs) => `/${team}/activity/${runId}`,
  home: () => "/home",
  profile: () => "/profile",
  insights: ({ team }: TeamArg) => `/${team}/insights`,
  integrations: ({ team }: TeamArg) => `/${team}/integrations`,
  manageTasks: ({ team }: TeamArg) => `/${team}/task-manager`,
  manageTasksEdit: ({ team, name, version }: ManageTaskTemplateArgs) => `/${team}/task-manager/${name}/${version}`,
  manageTasksYaml: ({ team, name, version }: ManageTaskTemplateArgs) =>
    `/${team}/task-manager/${name}/${version}/editor`,
  manageTeam: ({ team }: TeamArg) => `/${team}/manage`,
  manageTeamApprovers: ({ team }: TeamArg) => `/${team}/manage/approver-groups`,
  manageTeamWorkflows: ({ team }: TeamArg) => `/${team}/manage/workflows`,
  manageTeamLabels: ({ team }: TeamArg) => `/${team}/manage/labels`,
  manageTeamQuotas: ({ team }: TeamArg) => `/${team}/manage/quotas`,
  manageTeamSettings: ({ team }: TeamArg) => `/${team}/manage/settings`,
  manageTeamTokens: ({ team }: TeamArg) => `/${team}/manage/tokens`,
  manageTeamParameters: ({ team }: TeamArg) => `/${team}/parameters`,
  manageUsers: () => "/admin/users",
  properties: () => "/admin/parameters",
  schedule: () => "/schedule",
  settings: () => "/admin/settings",
  templateWorkflows: () => "/admin/template-workflows",
  adminTasks: () => "/admin/task-manager",
  adminTasksDetail: ({ name, version }: AdminTaskTemplateArgs) => `/admin/task-manager/${name}/${version}`,
  adminTasksEditor: ({ name, version }: AdminTaskTemplateArgs) => `/admin/task-manager/${name}/${version}/editor`,
  teamList: () => "/admin/teams",
  tokens: () => `/admin/tokens`,
  user: ({ userId }: UserIdArg) => `/admin/users/${userId}`,
  userLabels: ({ userId }: UserIdArg) => `/admin/users/${userId}/labels`,
  userSettings: ({ userId }: UserIdArg) => `/admin/users/${userId}/settings`,
  userList: () => "/admin/users",
  workflows: ({ team }: TeamArg) => `/${team}/workflows`,
  workflowActivity: ({ team, workflow }: TeamRouteArgs) => `/${team}/activity?page=0&size=10&workflows=${workflow}`,
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
  TeamManagementEnabled = "TeamManagementEnabled",
  TeamParametersEnabled = "TeamParametersEnabled",
  TeamTasksEnabled = "TeamTasksEnabled",
  UserManagementEnabled = "UserManagementEnabled",
  TeamQuotasEnabled = "TeamQuotasEnabled",
  WorkflowTokensEnabled = "WorkflowTokensEnabled",
  WorkflowTriggersEnabled = "WorkflowTriggersEnabled",
}

export const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };
