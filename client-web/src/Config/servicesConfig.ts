/*
 * This file contains all the service URLs and configurations.
 *
 * The model is to have a serviceUrl object that contains all the service URLs. (The browser-side
 * `resolver` axios object died with the BFF teardown - all reads/writes go through route
 * loaders/actions via Config/serverFetch, or the /res/* resource routes in Config/resourceRoutes.)
 *
 * This depends on the /server to mount the environment variables that are the root prefixes.
 */
//@ts-nocheck
import { Envs } from "Constants";

// Set defaults; overridden below in production by the SSR-injected _SERVER_DATA.
export let CORE_SERVICE_ENV_URL = "/api";

// SSR (see react-router.config.ts) runs this module in Node, where `window` doesn't exist yet -
// same guard as Config/appConfig.ts's APP_ROOT/CORE_ENV_URL. `import.meta.env.MODE === Envs.Prod`
// alone isn't a safe guard here: the SSR build itself runs in production mode, so that check
// passes and the `window` read below it still executes.
if (typeof window !== "undefined" && import.meta.env.MODE === Envs.Prod && window._SERVER_DATA) {
  CORE_SERVICE_ENV_URL = window._SERVER_DATA.CORE_SERVICE_ENV_URL;
}

export const PRODUCT_SERVICE_ENV_URL =
  typeof window !== "undefined" && import.meta.env.MODE === Envs.Prod && window._SERVER_DATA
    ? window._SERVER_DATA.PRODUCT_SERVICE_ENV_URL
    : "/api";

export const BASE_URL = `${PRODUCT_SERVICE_ENV_URL}`;

type IdArg = {
  id: string;
};

type NameArg = {
  name: string;
};

type WorkflowArg = {
  workflow: string;
};

type WorkspaceArg = {
  workspace: string;
};

type VersionArg = {
  version: string | number;
};

type ReplaceArg = {
  replace: boolean;
};

type QueryArg = {
  query: string;
};

export const serviceUrl = {
  // Sign-in flow. getAuthConfig is unauthenticated - the
  // signed-out page reads it to decide which sign-in surface (if any) to offer. postAuthExchange
  // mints the httpOnly bfs_ session cookie (empty body = proxy-forwarded identity; {idToken,
  // nonce} = direct OIDC login). postAuthLogout revokes the session and clears the cookie.
  getAuthConfig: () => `${BASE_URL}/auth/config`,
  postAuthExchange: () => `${BASE_URL}/auth/exchange`,
  postAuthLogout: () => `${BASE_URL}/auth/logout`,
  deleteToken: ({ tokenId }) => `${BASE_URL}/token/${tokenId}`,
  getFeatureFlags: () => `${BASE_URL}/features`,
  getNavigation: ({ query }: QueryArg) => `${BASE_URL}/navigation${query}`,
  getGlobalParameters: () => `${BASE_URL}/parameters`,
  // No single-parameter GET exists; this is only used to build the delete-by-name URL below.
  getGlobalParameter: ({ name }: NameArg) => `${BASE_URL}/parameters/${name}`,
  getGlobalTokens: () => `${BASE_URL}/token/query?types=global`,
  getManageWorkspacesCreate: () => `${BASE_URL}/workspace`,
  // TODO: no dedicated labels route; labels are now merged in via patchWorkspace's request body.
  getManageWorkspaceLabels: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/labels`,
  getContext: () => `${BASE_URL}/context`,
  getWorkspaces: ({ query }: QueryArg) => `${BASE_URL}/workspace/query${query ? "?" + query : ""}`,
  deleteWorkspaceQuotas: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/quotas`,
  getWorkspaceQuotaDefaults: () => `${BASE_URL}/workspace/quotas/default`,
  getTokens: ({ query }) => `${BASE_URL}/token/query${query ? "?" + query : ""}`,
  // Resources/actions/role presets a token's permission grid is allowed to offer - server-driven
  // so the picker can't drift from what is actually enforced.
  getTokenCatalog: ({ query }) => `${BASE_URL}/token/catalog${query ? "?" + query : ""}`,
  getUsers: ({ query }: QueryArg) => `${BASE_URL}/user/query${query ? "?" + query : ""}`,
  getUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  getUserWorkspaces: ({ userId }) => `${BASE_URL}/user/${userId}/workspaces`,
  deleteUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  getUserProfile: () => `${BASE_URL}/profile`,
  getIntegrations: ({ workspace }: WorkspaceArg) => `${BASE_URL}/integration${workspace ? "?workspace=" + workspace : ""}`,
  getTaskrunLog: ({ id }: IdArg) => `${BASE_URL}/taskrun/${id}/log`,
  postToken: () => `${BASE_URL}/token`,
  postWorkspaceValidateName: () => `${BASE_URL}/workspace/validate-name`,
  postWorkspace: () => `${BASE_URL}/workspace`,
  // TODO: quota reset is now DELETE workspace/{workspace}/quotas (see deleteWorkspaceQuotas); no POST .../quotas/reset route exists.
  postWorkspaceQuotasReset: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/quotas/reset`,
  resourceWorkspace: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}`,
  // TODO: create/update approver groups no longer exist as standalone routes (merged into patchWorkspace);
  // delete now takes a request body of group names, not a /{groupId} path segment.
  resourceApproverGroups: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/approvers`,
  putActivationApp: () => `${BASE_URL}/activate`,
  resourceSettings: () => `${BASE_URL}/settings`,
  resourceTrigger: () => `${BASE_URL}`,
  getGitHubAppInstallation: ({ id }: IdArg) => `${BASE_URL}/integration/github/installation${id ? "?id=" + id : ""}`,
  getGitHubAppInstallationForWorkspace: ({ workspace }: WorkspaceArg) =>
    `${BASE_URL}/integration/github/installation${workspace ? "?workspace=" + workspace : ""}`,
  postGitHubAppUnlink: () => `${BASE_URL}/integration/github/unlink`,
  schedule: {
    getCronValidation: ({ workspace, expression }) =>
      `${BASE_URL}/workspace/${workspace}/schedule/validate-cron?cron=${expression}`,
  },
  task: {
    // deleteArchiveTaskTemplate: ({ id }) => `${BASE_URL}/task/${id}`,
    queryTasks: ({ query }: QueryArg) => `${BASE_URL}/task/query${query ? "?" + query : ""}`,
    getTask: ({ name, version }: NameArg & Partial<VersionArg>) =>
      `${BASE_URL}/task/${name}${version ? `?version=${version}` : ""}`,
    getTaskChangelog: ({ name }: NameArg) => `${BASE_URL}/task/${name}/changelog`,
    putTask: ({ name, replace }: NameArg & Partial<ReplaceArg>) =>
      `${BASE_URL}/task/${name}?replace=${replace ? replace : false}`,
    postValidateYaml: () => `${BASE_URL}/task/validate`,
  },
  template: {
    getWorkflowTemplate: ({ name }: NameArg) => `${BASE_URL}/workflowtemplate/${name}`,
    getWorkflowTemplates: () => `${BASE_URL}/workflowtemplate/query`,
    postWorkflowTemplate: () => `${BASE_URL}/workflowtemplate`,
  },
  workspace: {
    deleteWorkspaceMembers: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/members`,
    leaveWorkspace: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/leave`,
    // TODO: no dedicated parameter create/list route; parameters are merged in via patchWorkspace's request body.
    resourceWorkspaceParameters: ({ workspace }) => `${BASE_URL}/workspace/${workspace}/parameters`,
    deleteWorkspaceParameter: ({ workspace, name }) => `${BASE_URL}/workspace/${workspace}/parameters/${name}`,
    getInsights: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
      `${BASE_URL}/workspace/${workspace}/insights${query ? "?" + query : ""}`,
    action: {
      getActionsSummary: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/action/summary${query ? "?" + query : ""}`,
      getActions: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/action/query${query ? "?" + query : ""}`,
      // Fetch by id only. `/action/query` filters on types/statuses/workflows/dates - NOT on
      // taskRunRef or workflowRunRef (WorkspaceActionControllerV2.query), and the by-TaskRun
      // route next to it is commented out server-side - so a TaskRun's approver detail is
      // reached via the `actionRef` result it carries, resolved through this route.
      getAction: ({ workspace, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${workspace}/action/${id}`,
      putAction: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/action`,
    },
    task: {
      // deleteArchiveTaskTemplate: ({ id }) => `${BASE_URL}/task/${id}`,
      queryTasks: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/task/query${query ? "?" + query : ""}`,
      getTask: ({ workspace, name, version }: WorkspaceArg & NameArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${workspace}/task/${name}${version ? `?version=${version}` : ""}`,
      getTaskChangelog: ({ workspace, name }: WorkspaceArg & NameArg) =>
        `${BASE_URL}/workspace/${workspace}/task/${name}/changelog`,
      putTask: ({ workspace, name, replace }: WorkspaceArg & NameArg & Partial<ReplaceArg>) =>
        `${BASE_URL}/workspace/${workspace}/task/${name}?replace=${replace ? replace : false}`,
      postValidateYaml: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/task/validate`,
    },
    workflow: {
      getWorkflow: ({ workspace, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}${version ? `?version=${version}` : ""}`,
      getWorkflows: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflow/query${query ? "?" + query : ""}`,
      getWorkflowCompose: ({ workspace, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/compose${version ? `?version=${version}` : ""}`,
      // NOTE: kind=workflowRun is no longer read server-side (compose only accepts ?version); left as-is, harmless extra param.
      getWorkflowComposeRun: ({ workspace, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/compose${version ? `?version=${version}&kind=workflowRun` : ""}`,
      getWorkflowChangelog: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/changelog`,
      postCreateWorkflow: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/workflow`,
      postDuplicateWorkflow: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/duplicate`,
      postSubmitWorkflow: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        // start=true: submit alone parks the run at ready (the engine's default is start=false),
        // and nothing starts a parked run any more - a run without workspaces starts at once,
        // one with workspaces waits for the dispatcher to provision and start it.
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/submit?start=true`,
      getAvailableParameters: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/available-parameters`,
      putApplyWorkflowCompose: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/compose`,
      putApplyWorkflow: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/workflow`,
      getExportWorkflow: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/export`,
      // TODO: no workflow-level validate-name route; only workspace names are validated (postWorkspaceValidateName).
      postValidateName: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/workflow/validate-name`,
    },
    workflowrun: {
      deleteCancelWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/cancel`,
      putRetryWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/retry`,
      putStartWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/start`,
      putPauseWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/pause`,
      putResumeWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/resume`,
      putFinalizeWorkflow: ({ workspace, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/${id}/finalize`,
      getWorkflowRunCount: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/count${query ? "?" + query : ""}`,
      getWorkflowRuns: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/workflowrun/query${query ? "?" + query : ""}`,
      getWorkflowRun: ({ workspace, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${workspace}/workflowrun/${id}`,
    },
    schedule: {
      getSchedules: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/schedule/query${query ? "?" + query : ""}`,
      getSchedule: ({ workspace, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${workspace}/schedule/${id}`,
      getSchedulesCalendars: ({ workspace, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${workspace}/schedule/calendars${query ? "?" + query : ""}`,
      deleteSchedule: ({ workspace, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${workspace}/schedule/${id}`,
      // getScheduleCalendar: ({ scheduleId, query }) =>
      //   `${BASE_URL}/schedule/${scheduleId}/calendar${query ? "?" + query : ""}`,
      putSchedule: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/schedule`,
      postSchedule: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/schedule`,
    },
  },
};
