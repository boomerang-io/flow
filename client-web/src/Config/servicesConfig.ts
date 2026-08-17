/*
 * This file contains all the service URLs and configurations.
 *
 * The model is to have a serviceUrl object that contains all the service URLs and a resolver object that holds all the queries and mutations.
 *
 * This depends on the /server to mount the environment variables that are the root prefixes.
 */
//@ts-nocheck
import axios from "axios";
import { Envs, HttpMethod } from "Constants";

// Set defaults, change them if Cypress is NOT defined
export let CORE_SERVICE_ENV_URL = "/api";

if (import.meta.env.MODE === Envs.Prod && window._SERVER_DATA) {
  CORE_SERVICE_ENV_URL = window._SERVER_DATA.CORE_SERVICE_ENV_URL;
}

export const PRODUCT_SERVICE_ENV_URL =
  import.meta.env.MODE === Envs.Prod && window._SERVER_DATA ? window._SERVER_DATA.PRODUCT_SERVICE_ENV_URL : "/api";

export const BASE_URL = `${PRODUCT_SERVICE_ENV_URL}`;
export const BASE_CORE_URL = CORE_SERVICE_ENV_URL;
export const BASE_CORE_USERS_URL = `${CORE_SERVICE_ENV_URL}/users`;

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
  team: string;
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
  deleteToken: ({ tokenId }) => `${BASE_URL}/token/${tokenId}`,
  getFeatureFlags: () => `${BASE_URL}/features`,
  getNavigation: ({ query }: QueryArg) => `${BASE_URL}/navigation${query}`,
  getGlobalParameters: () => `${BASE_URL}/parameters`,
  // No single-parameter GET exists; this is only used to build the delete-by-name URL below.
  getGlobalParameter: ({ name }: IdArg) => `${BASE_URL}/parameters/${name}`,
  getGlobalTokens: () => `${BASE_URL}/token/query?types=global`,
  getManageTeamsCreate: () => `${BASE_URL}/workspace`,
  // TODO: no dedicated labels route; labels are now merged in via patchTeam's request body.
  getManageTeamLabels: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/labels`,
  getContext: () => `${BASE_URL}/context`,
  getTeams: ({ query }: QueryArg) => `${BASE_URL}/workspace/query${query ? "?" + query : ""}`,
  deleteTeamQuotas: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/quotas`,
  getTeamQuotaDefaults: () => `${BASE_URL}/workspace/quotas/default`,
  getTokens: ({ query }) => `${BASE_URL}/token/query${query ? "?" + query : ""}`,
  getUsers: ({ query }: QueryArg) => `${BASE_URL}/user/query${query ? "?" + query : ""}`,
  getUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  deleteUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  getUserProfile: () => `${BASE_URL}/profile`,
  getUserProfileImage: ({ userEmail }) => `${BASE_CORE_USERS_URL}/image/${userEmail}`,
  getIntegrations: ({ team }: WorkspaceArg) => `${BASE_URL}/integration${team ? "?workspace=" + team : ""}`,
  getTaskrunLog: ({ id }: IdArg) => `${BASE_URL}/taskrun/${id}/log`,
  postToken: () => `${BASE_URL}/token`,
  postTeamValidateName: () => `${BASE_URL}/workspace/validate-name`,
  postTeam: () => `${BASE_URL}/workspace`,
  // TODO: quota reset is now DELETE workspace/{workspace}/quotas (see deleteTeamQuotas); no POST .../quotas/reset route exists.
  postTeamQuotasReset: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/quotas/reset`,
  resourceTeam: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}`,
  // TODO: create/update approver groups no longer exist as standalone routes (merged into patchTeam);
  // delete now takes a request body of group names, not a /{groupId} path segment.
  resourceApproverGroups: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/approvers`,
  putActivationApp: () => `${BASE_URL}/activate`,
  resourceSettings: () => `${BASE_URL}/settings`,
  resourceTrigger: () => `${BASE_URL}`,
  getGitHubAppInstallation: ({ id }: IdArg) => `${BASE_URL}/integration/github/installation${id ? "?id=" + id : ""}`,
  getGitHubAppInstallationForTeam: ({ team }: WorkspaceArg) =>
    `${BASE_URL}/integration/github/installation${team ? "?workspace=" + team : ""}`,
  postGitHubAppLink: () => `${BASE_URL}/integration/github/link`,
  postGitHubAppUnlink: () => `${BASE_URL}/integration/github/unlink`,
  schedule: {
    getCronValidation: ({ team, expression }) =>
      `${BASE_URL}/workspace/${team}/schedule/validate-cron?cron=${expression}`,
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
    // TODO: export route removed from the Workflow Template API; no longer reachable.
    getExportWorkflowTemplate: ({ name }: NameArg) => `${BASE_URL}/workflowtemplate/${name}/export`,
  },
  team: {
    deleteTeamMembers: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/members`,
    leaveTeam: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/leave`,
    // TODO: no dedicated parameter create/list route; parameters are merged in via patchTeam's request body.
    resourceTeamParameters: ({ team }) => `${BASE_URL}/workspace/${team}/parameters`,
    deleteTeamParameter: ({ team, name }) => `${BASE_URL}/workspace/${team}/parameters/${name}`,
    getInsights: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
      `${BASE_URL}/workspace/${team}/insights${query ? "?" + query : ""}`,
    action: {
      getActionsSummary: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/action/summary${query ? "?" + query : ""}`,
      getActions: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/action/query${query ? "?" + query : ""}`,
      putAction: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/action`,
    },
    task: {
      // deleteArchiveTaskTemplate: ({ id }) => `${BASE_URL}/task/${id}`,
      queryTasks: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/task/query${query ? "?" + query : ""}`,
      getTask: ({ team, name, version }: WorkspaceArg & NameArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${team}/task/${name}${version ? `?version=${version}` : ""}`,
      getTaskChangelog: ({ team, name }: WorkspaceArg & NameArg) =>
        `${BASE_URL}/workspace/${team}/task/${name}/changelog`,
      putTask: ({ team, name, replace }: WorkspaceArg & NameArg & Partial<ReplaceArg>) =>
        `${BASE_URL}/workspace/${team}/task/${name}?replace=${replace ? replace : false}`,
      postValidateYaml: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/task/validate`,
    },
    workflow: {
      getWorkflow: ({ team, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}${version ? `?version=${version}` : ""}`,
      getWorkflows: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/workflow/query${query ? "?" + query : ""}`,
      getWorkflowCompose: ({ team, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/compose${version ? `?version=${version}` : ""}`,
      // NOTE: kind=workflowRun is no longer read server-side (compose only accepts ?version); left as-is, harmless extra param.
      getWorkflowComposeRun: ({ team, workflow, version }: WorkspaceArg & WorkflowArg & Partial<VersionArg>) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/compose${version ? `?version=${version}&kind=workflowRun` : ""}`,
      getWorkflowChangelog: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/changelog`,
      postCreateWorkflow: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/workflow`,
      postDuplicateWorkflow: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/duplicate`,
      postSubmitWorkflow: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/submit`,
      getAvailableParameters: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/available-parameters`,
      putApplyWorkflowCompose: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/compose`,
      putApplyWorkflow: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/workflow`,
      getExportWorkflow: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
        `${BASE_URL}/workspace/${team}/workflow/${workflow}/export`,
      // TODO: no workflow-level validate-name route; only workspace names are validated (postTeamValidateName).
      postValidateName: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/workflow/validate-name`,
    },
    workflowrun: {
      deleteCancelWorkflow: ({ team, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${team}/workflowrun/${id}/cancel`,
      putRetryWorkflow: ({ team, id }: WorkspaceArg & IdArg) =>
        `${BASE_URL}/workspace/${team}/workflowrun/${id}/retry`,
      getWorkflowRunCount: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/workflowrun/count${query ? "?" + query : ""}`,
      getWorkflowRuns: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/workflowrun/query${query ? "?" + query : ""}`,
      getWorkflowRun: ({ team, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${team}/workflowrun/${id}`,
    },
    schedule: {
      getSchedules: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/schedule/query${query ? "?" + query : ""}`,
      getSchedule: ({ team, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${team}/schedule/${id}`,
      getSchedulesCalendars: ({ team, query }: WorkspaceArg & Partial<QueryArg>) =>
        `${BASE_URL}/workspace/${team}/schedule/calendars${query ? "?" + query : ""}`,
      deleteSchedule: ({ team, id }: WorkspaceArg & IdArg) => `${BASE_URL}/workspace/${team}/schedule/${id}`,
      // getScheduleCalendar: ({ scheduleId, query }) =>
      //   `${BASE_URL}/schedule/${scheduleId}/calendar${query ? "?" + query : ""}`,
      putSchedule: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/schedule`,
      postSchedule: ({ team }: WorkspaceArg) => `${BASE_URL}/workspace/${team}/schedule`,
    },
  },
};

export const resolver = {
  query: (url) => () => axios.get(url).then((response) => response.data),
  queryYaml: (url) => () =>
    axios
      .get(url, {
        headers: {
          accept: "application/x-yaml",
        },
      })
      .then((response) => response.data),
  postMutation: (request) => axios.post(request),
  patchMutation: (request) => axios.patch(request),
  putMutation: (request) => axios.put(request),
  // Approver groups are now deleted in bulk by name, not by a /{groupId} path segment.
  deleteApproverGroup: ({ team, groupId }) =>
    axios.delete(serviceUrl.resourceApproverGroups({ team }), { data: [groupId] }),
  // deleteArchiveTaskTemplate: ({ id }) => axios.delete(serviceUrl.deleteArchiveTaskTemplate({ id })),
  putRetryWorkflowRun: ({ team, id }) => axios.put(serviceUrl.team.workflowrun.putRetryWorkflow({ team, id })),
  deleteCancelWorkflowRun: ({ team, id }) =>
    axios.delete(serviceUrl.team.workflowrun.deleteCancelWorkflow({ team, id })),
  deleteGlobalParameter: ({ name }) => axios.delete(serviceUrl.getGlobalParameter({ name })),
  deleteTeamMembers: ({ team, body }) =>
    axios({ url: serviceUrl.team.deleteTeamMembers({ team }), data: body, method: HttpMethod.Delete }),
  deleteTeamParameter: ({ team, name }) => axios.delete(serviceUrl.team.deleteTeamParameter({ team, name })),
  deleteWorkflow: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
    axios.delete(serviceUrl.team.workflow.getWorkflow({ team, workflow })),
  deleteWorkflowTemplate: ({ name }) => axios.delete(serviceUrl.template.getWorkflowTemplate({ name })),
  leaveTeam: ({ team }) => axios.delete(serviceUrl.team.leaveTeam({ team })),
  deleteSchedule: ({ team, id }) => axios.delete(serviceUrl.team.schedule.deleteSchedule({ team, id })),
  deleteTeam: ({ team }: WorkspaceArg) => axios.delete(serviceUrl.resourceTeam({ team })),
  deleteToken: ({ tokenId }) => axios.delete(serviceUrl.deleteToken({ tokenId })),
  deleteUser: ({ userId }) => axios.delete(serviceUrl.deleteUser({ userId })),
  // Params are now updated in bulk via PUT (no id in the path); the request body carries the full parameter.
  patchGlobalParameter: ({ body }) =>
    axios({ url: serviceUrl.getGlobalParameters(), data: body, method: HttpMethod.Put }),
  patchTeam: ({ team, body }) => axios.patch(serviceUrl.resourceTeam({ team }), body),
  patchManageTeamLabels: ({ team, body }) => axios.patch(serviceUrl.getManageTeamLabels({ team }), body),
  patchProfile: ({ body }) => axios({ url: serviceUrl.getUserProfile(), data: body, method: HttpMethod.Patch }),
  patchManageUser: ({ body, userId }) =>
    axios({ url: serviceUrl.getUser({ userId }), data: body, method: HttpMethod.Patch }),
  putSchedule: ({ team, body }) => axios.put(serviceUrl.team.schedule.putSchedule({ team }), body),
  postTeam: ({ body }) => axios.post(serviceUrl.postTeam(), body),
  postTeamValidateName: ({ body }) => axios.post(serviceUrl.postTeamValidateName(), body),
  postWorkflowValidateName: ({ team, body }) => axios.post(serviceUrl.team.workflow.postValidateName({ team }), body),
  postValidateYaml: ({ body }) =>
    axios({
      method: HttpMethod.Post,
      url: serviceUrl.task.postValidateYaml(),
      data: body,
      headers: {
        "content-type": "application/x-yaml",
      },
    }),
  // TODO: no dedicated parameter-update route; serviceUrl.getTeamParameter was never defined (pre-existing dead
  // reference) and the underlying capability moved to patchTeam's request body regardless.
  patchTeamParameter: ({ team, key, body }) =>
    axios({
      url: serviceUrl.getTeamParameter({ team, key }),
      data: body,
      method: HttpMethod.Patch,
    }),
  // TODO: create/update approver groups no longer exist as standalone routes; use patchTeam.
  postApproverGroupRequest: ({ body, team }) =>
    axios({
      url: serviceUrl.resourceApproverGroups({ team }),
      data: body,
      method: HttpMethod.Post,
    }),
  postCreateTemplate: ({ body }) =>
    axios({ url: serviceUrl.template.postWorkflowTemplate(), data: body, method: HttpMethod.Post }),
  postCreateWorkflow: ({ team, body }) =>
    axios({ url: serviceUrl.team.workflow.postCreateWorkflow({ team }), data: body, method: HttpMethod.Post }),
  postDuplicateWorkflow: ({ team, workflow }: WorkspaceArg & WorkflowArg) =>
    axios.post(serviceUrl.team.workflow.postDuplicateWorkflow({ team, workflow })),
  // TODO: Workflow Template duplication was removed from the API; serviceUrl.postDuplicateWorkflow (top-level)
  // was also never defined (pre-existing dead reference).
  postTemplateWorkflow: ({ workflowId, body }) => axios.post(serviceUrl.postDuplicateWorkflow({ workflowId }), body),
  postToken: ({ body }) => axios({ url: serviceUrl.postToken(), data: body, method: HttpMethod.Post }),
  putApplyTaskTemplate: ({ name, replace, body }) =>
    axios({ url: serviceUrl.task.putTask({ name, replace }), data: body, method: HttpMethod.Put }),
  putApplyTeamTaskTemplate: ({ team, name, replace, body }) =>
    axios({ url: serviceUrl.team.task.putTask({ team, name, replace }), data: body, method: HttpMethod.Put }),
  putApplyTaskTemplateYaml: ({ name, replace, body }) =>
    axios({
      url: serviceUrl.task.putTask({ name, replace }),
      data: body,
      method: HttpMethod.Put,
      headers: { "content-type": "application/x-yaml" },
    }),
  putApplyTeamTaskTemplateYaml: ({ team, name, replace, body }) =>
    axios({
      url: serviceUrl.team.task.putTask({ team, name, replace }),
      data: body,
      method: HttpMethod.Put,
      headers: { "content-type": "application/x-yaml" },
    }),
  postCreateTeam: ({ body }) => axios({ url: serviceUrl.getManageTeamsCreate(), data: body, method: HttpMethod.Post }),
  putApplyWorkflow: ({ team, body }) =>
    axios.put<Workflow, Workflow>(serviceUrl.team.workflow.putApplyWorkflow({ team }), body),
  putApplyWorkflowCompose: ({ team, workflow, body }) =>
    axios.put<Workflow, Workflow>(serviceUrl.team.workflow.putApplyWorkflowCompose({ team, workflow }), body),
  postSubmitWorkflow: ({ team, workflow, body }) =>
    axios.post(serviceUrl.team.workflow.postSubmitWorkflow({ team, workflow }), body),
  postGlobalParameter: ({ body }) =>
    axios({ url: serviceUrl.getGlobalParameters(), data: body, method: HttpMethod.Post }),
  postSchedule: ({ team, body }) => axios.post(serviceUrl.team.schedule.postSchedule({ team }), body),
  // TODO: no dedicated parameter-create route; use patchTeam.
  postTeamParameter: ({ team, body }) =>
    axios({ url: serviceUrl.team.resourceTeamParameters({ team }), data: body, method: HttpMethod.Post }),
  putActivationApp: ({ body }) =>
    axios({
      method: HttpMethod.Put,
      url: serviceUrl.putActivationApp(),
      data: body,
      validateStatus: (status) => status >= 200 && status < 300,
    }),
  // TODO: no PUT approver-group route; use patchTeam.
  putApproverGroupRequest: ({ body, team }) =>
    axios({
      url: serviceUrl.resourceApproverGroups({ team }),
      data: body,
      method: HttpMethod.Put,
    }),
  putPlatformSettings: ({ body }) => axios.put(serviceUrl.resourceSettings(), body),
  putRestoreTaskTemplate: ({ id }: IdArg) => axios.put(serviceUrl.putRestoreTaskTemplate({ id })),
  patchUpdateTeam: ({ team, body }) => axios.patch(serviceUrl.resourceTeam({ team }), body),
  deleteTeamQuotas: ({ team }) => axios({ url: serviceUrl.deleteTeamQuotas({ team }), method: HttpMethod.Delete }),
  putAction: ({ team, body }) =>
    axios({ url: serviceUrl.team.action.putAction({ team }), data: body, method: HttpMethod.Put }),
  postGitHubAppLink: ({ body }) => axios.post(serviceUrl.postGitHubAppLink(), body),
  postGitHubAppUnlink: ({ body }) => axios.post(serviceUrl.postGitHubAppUnlink(), body),
};
