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
  deleteToken: ({ tokenId }) => `${BASE_URL}/token/${tokenId}`,
  getFeatureFlags: () => `${BASE_URL}/features`,
  getNavigation: ({ query }: QueryArg) => `${BASE_URL}/navigation${query}`,
  getGlobalParameters: () => `${BASE_URL}/parameters`,
  // No single-parameter GET exists; this is only used to build the delete-by-name URL below.
  getGlobalParameter: ({ name }: IdArg) => `${BASE_URL}/parameters/${name}`,
  getGlobalTokens: () => `${BASE_URL}/token/query?types=global`,
  getManageWorkspacesCreate: () => `${BASE_URL}/workspace`,
  // TODO: no dedicated labels route; labels are now merged in via patchWorkspace's request body.
  getManageWorkspaceLabels: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/labels`,
  getContext: () => `${BASE_URL}/context`,
  getWorkspaces: ({ query }: QueryArg) => `${BASE_URL}/workspace/query${query ? "?" + query : ""}`,
  deleteWorkspaceQuotas: ({ workspace }: WorkspaceArg) => `${BASE_URL}/workspace/${workspace}/quotas`,
  getWorkspaceQuotaDefaults: () => `${BASE_URL}/workspace/quotas/default`,
  getTokens: ({ query }) => `${BASE_URL}/token/query${query ? "?" + query : ""}`,
  getUsers: ({ query }: QueryArg) => `${BASE_URL}/user/query${query ? "?" + query : ""}`,
  getUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  deleteUser: ({ userId }) => `${BASE_URL}/user/${userId}`,
  getUserProfile: () => `${BASE_URL}/profile`,
  getUserProfileImage: ({ userEmail }) => `${BASE_CORE_USERS_URL}/image/${userEmail}`,
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
  postGitHubAppLink: () => `${BASE_URL}/integration/github/link`,
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
    // TODO: export route removed from the Workflow Template API; no longer reachable.
    getExportWorkflowTemplate: ({ name }: NameArg) => `${BASE_URL}/workflowtemplate/${name}/export`,
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
        `${BASE_URL}/workspace/${workspace}/workflow/${workflow}/submit`,
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
  deleteApproverGroup: ({ workspace, groupId }) =>
    axios.delete(serviceUrl.resourceApproverGroups({ workspace }), { data: [groupId] }),
  // deleteArchiveTaskTemplate: ({ id }) => axios.delete(serviceUrl.deleteArchiveTaskTemplate({ id })),
  putRetryWorkflowRun: ({ workspace, id }) => axios.put(serviceUrl.workspace.workflowrun.putRetryWorkflow({ workspace, id })),
  deleteCancelWorkflowRun: ({ workspace, id }) =>
    axios.delete(serviceUrl.workspace.workflowrun.deleteCancelWorkflow({ workspace, id })),
  deleteGlobalParameter: ({ name }) => axios.delete(serviceUrl.getGlobalParameter({ name })),
  deleteWorkspaceMembers: ({ workspace, body }) =>
    axios({ url: serviceUrl.workspace.deleteWorkspaceMembers({ workspace }), data: body, method: HttpMethod.Delete }),
  deleteWorkspaceParameter: ({ workspace, name }) => axios.delete(serviceUrl.workspace.deleteWorkspaceParameter({ workspace, name })),
  deleteWorkflow: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
    axios.delete(serviceUrl.workspace.workflow.getWorkflow({ workspace, workflow })),
  deleteWorkflowTemplate: ({ name }) => axios.delete(serviceUrl.template.getWorkflowTemplate({ name })),
  leaveWorkspace: ({ workspace }) => axios.delete(serviceUrl.workspace.leaveWorkspace({ workspace })),
  deleteSchedule: ({ workspace, id }) => axios.delete(serviceUrl.workspace.schedule.deleteSchedule({ workspace, id })),
  deleteWorkspace: ({ workspace }: WorkspaceArg) => axios.delete(serviceUrl.resourceWorkspace({ workspace })),
  deleteToken: ({ tokenId }) => axios.delete(serviceUrl.deleteToken({ tokenId })),
  deleteUser: ({ userId }) => axios.delete(serviceUrl.deleteUser({ userId })),
  // Params are now updated in bulk via PUT (no id in the path); the request body carries the full parameter.
  patchGlobalParameter: ({ body }) =>
    axios({ url: serviceUrl.getGlobalParameters(), data: body, method: HttpMethod.Put }),
  patchWorkspace: ({ workspace, body }) => axios.patch(serviceUrl.resourceWorkspace({ workspace }), body),
  patchManageWorkspaceLabels: ({ workspace, body }) => axios.patch(serviceUrl.getManageWorkspaceLabels({ workspace }), body),
  patchProfile: ({ body }) => axios({ url: serviceUrl.getUserProfile(), data: body, method: HttpMethod.Patch }),
  patchManageUser: ({ body, userId }) =>
    axios({ url: serviceUrl.getUser({ userId }), data: body, method: HttpMethod.Patch }),
  putSchedule: ({ workspace, body }) => axios.put(serviceUrl.workspace.schedule.putSchedule({ workspace }), body),
  postWorkspace: ({ body }) => axios.post(serviceUrl.postWorkspace(), body),
  postWorkspaceValidateName: ({ body }) => axios.post(serviceUrl.postWorkspaceValidateName(), body),
  postWorkflowValidateName: ({ workspace, body }) => axios.post(serviceUrl.workspace.workflow.postValidateName({ workspace }), body),
  postValidateYaml: ({ body }) =>
    axios({
      method: HttpMethod.Post,
      url: serviceUrl.task.postValidateYaml(),
      data: body,
      headers: {
        "content-type": "application/x-yaml",
      },
    }),
  // TODO: no dedicated parameter-update route; serviceUrl.getWorkspaceParameter was never defined (pre-existing dead
  // reference) and the underlying capability moved to patchWorkspace's request body regardless.
  patchWorkspaceParameter: ({ workspace, key, body }) =>
    axios({
      url: serviceUrl.getWorkspaceParameter({ workspace, key }),
      data: body,
      method: HttpMethod.Patch,
    }),
  // TODO: create/update approver groups no longer exist as standalone routes; use patchWorkspace.
  postApproverGroupRequest: ({ body, workspace }) =>
    axios({
      url: serviceUrl.resourceApproverGroups({ workspace }),
      data: body,
      method: HttpMethod.Post,
    }),
  postCreateTemplate: ({ body }) =>
    axios({ url: serviceUrl.template.postWorkflowTemplate(), data: body, method: HttpMethod.Post }),
  postCreateWorkflow: ({ workspace, body }) =>
    axios({ url: serviceUrl.workspace.workflow.postCreateWorkflow({ workspace }), data: body, method: HttpMethod.Post }),
  postDuplicateWorkflow: ({ workspace, workflow }: WorkspaceArg & WorkflowArg) =>
    axios.post(serviceUrl.workspace.workflow.postDuplicateWorkflow({ workspace, workflow })),
  // TODO: Workflow Template duplication was removed from the API; serviceUrl.postDuplicateWorkflow (top-level)
  // was also never defined (pre-existing dead reference).
  postTemplateWorkflow: ({ workflowId, body }) => axios.post(serviceUrl.postDuplicateWorkflow({ workflowId }), body),
  postToken: ({ body }) => axios({ url: serviceUrl.postToken(), data: body, method: HttpMethod.Post }),
  putApplyTaskTemplate: ({ name, replace, body }) =>
    axios({ url: serviceUrl.task.putTask({ name, replace }), data: body, method: HttpMethod.Put }),
  putApplyWorkspaceTaskTemplate: ({ workspace, name, replace, body }) =>
    axios({ url: serviceUrl.workspace.task.putTask({ workspace, name, replace }), data: body, method: HttpMethod.Put }),
  putApplyTaskTemplateYaml: ({ name, replace, body }) =>
    axios({
      url: serviceUrl.task.putTask({ name, replace }),
      data: body,
      method: HttpMethod.Put,
      headers: { "content-type": "application/x-yaml" },
    }),
  putApplyWorkspaceTaskTemplateYaml: ({ workspace, name, replace, body }) =>
    axios({
      url: serviceUrl.workspace.task.putTask({ workspace, name, replace }),
      data: body,
      method: HttpMethod.Put,
      headers: { "content-type": "application/x-yaml" },
    }),
  postCreateWorkspace: ({ body }) => axios({ url: serviceUrl.getManageWorkspacesCreate(), data: body, method: HttpMethod.Post }),
  putApplyWorkflow: ({ workspace, body }) =>
    axios.put<Workflow, Workflow>(serviceUrl.workspace.workflow.putApplyWorkflow({ workspace }), body),
  putApplyWorkflowCompose: ({ workspace, workflow, body }) =>
    axios.put<Workflow, Workflow>(serviceUrl.workspace.workflow.putApplyWorkflowCompose({ workspace, workflow }), body),
  postSubmitWorkflow: ({ workspace, workflow, body }) =>
    axios.post(serviceUrl.workspace.workflow.postSubmitWorkflow({ workspace, workflow }), body),
  postGlobalParameter: ({ body }) =>
    axios({ url: serviceUrl.getGlobalParameters(), data: body, method: HttpMethod.Post }),
  postSchedule: ({ workspace, body }) => axios.post(serviceUrl.workspace.schedule.postSchedule({ workspace }), body),
  // TODO: no dedicated parameter-create route; use patchWorkspace.
  postWorkspaceParameter: ({ workspace, body }) =>
    axios({ url: serviceUrl.workspace.resourceWorkspaceParameters({ workspace }), data: body, method: HttpMethod.Post }),
  putActivationApp: ({ body }) =>
    axios({
      method: HttpMethod.Put,
      url: serviceUrl.putActivationApp(),
      data: body,
      validateStatus: (status) => status >= 200 && status < 300,
    }),
  // TODO: no PUT approver-group route; use patchWorkspace.
  putApproverGroupRequest: ({ body, workspace }) =>
    axios({
      url: serviceUrl.resourceApproverGroups({ workspace }),
      data: body,
      method: HttpMethod.Put,
    }),
  putPlatformSettings: ({ body }) => axios.put(serviceUrl.resourceSettings(), body),
  putRestoreTaskTemplate: ({ id }: IdArg) => axios.put(serviceUrl.putRestoreTaskTemplate({ id })),
  patchUpdateWorkspace: ({ workspace, body }) => axios.patch(serviceUrl.resourceWorkspace({ workspace }), body),
  deleteWorkspaceQuotas: ({ workspace }) => axios({ url: serviceUrl.deleteWorkspaceQuotas({ workspace }), method: HttpMethod.Delete }),
  putAction: ({ workspace, body }) =>
    axios({ url: serviceUrl.workspace.action.putAction({ workspace }), data: body, method: HttpMethod.Put }),
  postGitHubAppLink: ({ body }) => axios.post(serviceUrl.postGitHubAppLink(), body),
  postGitHubAppUnlink: ({ body }) => axios.post(serviceUrl.postGitHubAppUnlink(), body),
};
