import { inflections } from "inflected";
import { Server, Serializer, Model, Response } from "miragejs";
import queryString from "query-string";
import { v4 as uuid } from "uuid";
import { serviceUrl, BASE_URL } from "Config/servicesConfig";
import * as fixtures from "./fixtures";

export function startApiServer({ environment = "test", timing = 0 } = {}) {
  inflections("en", function (inflect) {
    // Prevent pluralization bc our apis are weird
    inflect.irregular("activity", "activity");
    inflect.irregular("config", "config");
    inflect.irregular("insights", "insights");
    inflect.irregular("flowNavigation", "flowNavigation");
  });

  return new Server({
    environment,
    // Load in mock data
    fixtures,
    // Return the data as is, don't add a root key
    serializers: {
      application: Serializer.extend({
        root: false,
        embed: true,
      }),
    },
    // Register the data as a model so we can use the schema
    models: {
      activity: Model,
      approverGroups: Model,
      changelog: Model,
      globalParams: Model,
      featureFlag: Model,
      insights: Model,
      integrations: Model,
      installations: Model,
      manageWorkspace: Model,
      manageUser: Model,
      quotas: Model,
      revision: Model,
      setting: Model,
      summary: Model,
      task: Model,
      taskYaml: Model,
      workspace: Model,
      workspaceApproverUsers: Model,
      workspaceNameValidate: Model,
      workspaceProperties: Model,
      tokens: Model,
      flowNavigation: Model,
      workflowCompose: Model,
      workflowCalendar: Model,
      workflowSchedules: Model,
    },

    routes() {
      // Control how long the responses take to resolve
      this.timing = timing;

      // Allow unhandled requests on the current domain to pass through
      this.passthrough();

      this.get("/info", () => []);

      /**
       * Simple GET of static data
       */
      this.get(serviceUrl.getUserProfile(), (schema) => {
        return schema.db.profile[0];
      });

      this.get(serviceUrl.getContext(), (schema) => {
        return schema.db.platformConfig[0];
      });

      this.get(`${BASE_URL}/navigation`, (schema) => {
        return schema.db.flowNavigation;
      });

      // this.get(serviceUrl.getMyWorkspaces({ query: null }), (schema) => {
      //   return schema.db.myWorkspaces[0];
      // });

      this.get(serviceUrl.getFeatureFlags(), (schema) => {
        return schema.db.featureFlags[0];
      });

      this.get(serviceUrl.workspace.schedule.getSchedule({ workspace: ":workspace", query: null }), (schema) => {
        return schema.db.workflowSchedules;
      });

      this.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace", query: null }), (schema) => {
        return schema.db.workflowSchedules[0];
      });

      this.get(serviceUrl.workspace.workflow.getAvailableParameters({ workspace: ":workspace", name: ":name" }), (schema) => {
        return schema.db.availableParameters[0].data;
      });

      this.get(serviceUrl.template.getWorkflowTemplates(), (schema) => {
        return schema.db.workflowTemplates[0];
      });

      this.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: null, query: null }), (schema) => {
        return schema.db.workflows[0];
      });

      // this.get(serviceUrl.getWorkspaceQuotas({ id: ":id" }), (schema) => {
      //   return schema.db.quotas[0];
      // });

      this.get(serviceUrl.getWorkspaces({ query: null }), (schema) => {
        return schema.db.workspaces[0];
      });

      this.put(serviceUrl.putActivationApp(), () => {
        return {};
      });

      /**
       * Global Parameters
       */

      this.get(serviceUrl.getGlobalParameters({ query: null }), (schema) => {
        return schema.db.globalParams;
      });
      this.post(serviceUrl.getGlobalParameters(), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        schema.globalParams.create({ id: uuid(), ...body });
        return schema.globalParams.all();
      });

      this.patch(serviceUrl.getGlobalParameter({ id: ":id" }), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        let { id } = request.params;
        let param = schema.globalParams.find(id);
        param.update({ ...body });
      });

      this.delete(serviceUrl.getGlobalParameter({ id: ":id" }), (schema, request) => {
        let { id } = request.params;
        schema.db.globalParams.remove({ id });
      });

      /**
       * Workspace Propertiies
       */
      this.get(serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace" }), (schema, request) => {
        let { workspace } = request.params;
        let property = schema.workspaceProperties.find(workspace);
        return property && property.properties ? property.properties : [];
      });
      this.post(serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace" }), (schema, request) => {
        /**
         * find workspace record, update the list of properties for that workspace
         */
        let { workspace } = request.params;
        let body = JSON.parse(request.requestBody);
        let activeWorkspaceProperty = schema.workspaceProperties.find(workspace);
        let currentProperties = activeWorkspaceProperty.attrs.properties;
        currentProperties.push({ id: uuid(), ...body });
        activeWorkspaceProperty.update({ properties: currentProperties });
        return schema.workspaceProperties.all();
      });
      this.patch(
        serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace", configurationId: ":configurationId" }),
        (schema, request) => {
          /**
           * find workspace record, update the list of properties for that workspace
           */
          let { workspace, configurationId } = request.params;
          let body = JSON.parse(request.requestBody);
          let activeWorkspaceProperty = schema.workspaceProperties.find(workspace);
          let currentProperties = activeWorkspaceProperty.attrs.properties;
          let foundIndex = currentProperties.findIndex((prop) => prop.id === configurationId);
          currentProperties[foundIndex] = body;
          activeWorkspaceProperty.update({ properties: currentProperties });
          return schema.workspaceProperties.all();
        },
      );
      this.delete(
        serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace", configurationId: ":configurationId" }),
        (schema, request) => {
          /**
           * find workspace record, update the list of properties for that workspace
           */
          let { workspace, configurationId } = request.params;
          let activeWorkspaceProperty = schema.workspaceProperties.find(workspace);
          let currentProperties = activeWorkspaceProperty.attrs.properties;
          let newProperties = currentProperties.filter((prop) => prop.id !== configurationId);
          activeWorkspaceProperty.update({ properties: newProperties });
          return schema.workspaceProperties.all();
        },
      );

      /**
       * Insights
       */
      this.get(serviceUrl.workspace.getInsights({ workspace: ":workspace", query: null }), (schema, request) => {
        //grab the querystring from the end of the request url
        const query = request.url.substring(14);
        // eslint-disable-next-line
        const { fromDate = null, toDate = null, workspace = null } = queryString.parse(query);
        const activeWorkspace = workspace && schema.db.myWorkspaces.find(workspace);
        let activeExecutions = activeWorkspace && schema.db.insights[0].executions.filter((t) => t.name === workspace.name);
        return activeExecutions ? { ...schema.db.insights[0], executions: activeExecutions } : schema.db.insights[0];
      });

      /**
       * Tasks
       */
      this.get(serviceUrl.task.getTask({ name: ":name" }), (schema, request) => {
        console.log(request.requestHeaders);
        if (request.requestHeaders["Accept"] === "application/x-yaml") {
          return schema.db.taskYaml[0].yaml;
        } else {
          return schema.db.task[0].content.find((t) => t.name === request.params.name);
        }
      });
      this.get(serviceUrl.task.getTaskChangelog({ name: ":name" }), (schema) => {
        const response = [
          {
            author: "Bob",
            reason: "Add new task",
            date: "2023-08-16T22:34:05.234+00:00",
            version: 1,
          },
          {
            author: "Jenny",
            reason: "Update task to undo Bob's work",
            date: "2023-08-17T22:34:05.234+00:00",
            version: 2,
          },
        ];
        return response;
      });
      const taskPath = serviceUrl.task.queryTasks({ query: null });
      this.get(taskPath, (schema) => {
        return schema.db.task[0];
      });
      this.put(taskPath, (schema, request) => {
        let body = JSON.parse(request.requestBody);
        let task = schema.task.find(body.id);
        task.revisions.push(body);
        task.update({ ...body });
        return task;
      });
      this.post(serviceUrl.task.postValidateYaml(), (schema) => {
        return new Response(200, {}, { errors: ["Name is already taken"] });
      });
      this.put(serviceUrl.task.putTask({ replace: "true", workspace: ":workspace" }), (schema, request) => {
        return {};
      });

      /**
       * Workflows
       */
      this.post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace: ":workspace" }), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        let workflow = { ...body, id: uuid(), createdDate: Date.now(), revisionCount: 1, status: "active" };
        if (body.flowWorkspaceId) {
          let flowWorkspace = schema.myWorkspaces.findBy({ id: body.flowWorkspaceId });
          const workspaceWorkflows = [...flowWorkspace.workflows];
          workspaceWorkflows.push(workflow);
          flowWorkspace.update({ workflows: workspaceWorkflows });
          return schema.summaries.create(workflow);
        }
        return {};
      });

      this.get(
        serviceUrl.workspace.workflow.getWorkflowCompose({ workspace: ":workspace", name: ":name", version: null }),
        (schema, request) => {
          let { id } = request.params;
          return schema.db.workflowCompose.findBy({ id });
        },
      );

      this.del(serviceUrl.workspace.workflow.getWorkflow({ workspace: ":workspace", name: ":name" }), (schema, request) => {
        let { name } = request.params;
        let flowWorkspace = schema.myWorkspaces.where((workspace) => workspace.workflows.find((workflow) => workflow.name === name));
        let { attrs } = flowWorkspace.models[0];
        const workspaceWorkflows = attrs.workflows.filter((workflow) => workflow.name !== name);
        flowWorkspace.update({ workflows: workspaceWorkflows });
        return schema.db.summaries.remove({ name: name });
      });

      //Workflow Config Cron

      this.get(`${BASE_URL}/workflow/validate/cron`, () => {
        return {
          valid: true,
        };
      });

      // Workflow Changelog
      this.get(serviceUrl.workspace.workflow.getWorkflowChangelog({ workspace: ":workspace", name: ":name" }), (schema, request) => {
        return schema.db.changelogs;
      });

      /**
       * Activity
       */
      this.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace", query: null }), (schema) => {
        return schema.db.workflowRuns[0];
      });

      this.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace", query: null }), (schema, request) => {
        return schema.db.workflowRunCount[0];
      });

      this.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), (schema, request) => {
        return schema.db.workflowExecution[0];
      });

      this.post(
        serviceUrl.workspace.workflow.postSubmitWorkflow({ workspace: ":workspace", name: ":name", body: null }),
        (schema, request) => {
          return schema.db.workflowExecution[0];
        },
      );

      this.delete(
        serviceUrl.workspace.workflowrun.deleteCancelWorkflow({ workspace: ":workspace", runId: ":id" }),
        (schema, request) => {
          return {};
        },
      );

      /**
       * Actions
       */
      this.get(serviceUrl.workspace.action.getActionsSummary({ workspace: ":workspace", query: null }), (schema) => {
        return schema.db.actionsSummary[0];
      });

      this.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace", query: null }), (schema, request) => {
        const { type } = request.queryParams;
        if (type === "approval") return schema.db.approvals[0];
        if (type === "task") return schema.db.manualTasks[0];
        return {};
      });

      this.put(serviceUrl.workspace.action.putAction(), () => {
        return {};
      });

      /**
       * Approvers Group
       */
      this.get(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), (schema) => {
        return schema.db.approverGroups;
      });

      //Delete approver group
      this.delete(serviceUrl.resourceApproverGroups({ workspace: ":workspace", groupId: ":groupId" }), (schema, request) => {
        const { groupId } = request.params;
        const approverGroup = schema.approverGroups.find(groupId);
        approverGroup.destroy();
      });

      //Create approver group
      this.post(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), (schema, request) => {
        const body = JSON.parse(request.requestBody);
        schema.approverGroups.create({ groupId: uuid(), ...body });
        return schema.approverGroups.all();
      });

      //Update approver group
      this.put(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), (schema, request) => {
        return {};
      });

      /**
       * Manage Workspace
       */

      this.post(serviceUrl.postWorkspaceValidateName(), (schema, request) => {
        return new Response(422, {}, { errors: ["Name is already taken"] });
      });

      this.get(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), (schema, request) => {
        // let { workspaceId } = request.params;
        return schema.db.workspace[0];
      });

      this.patch(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), (schema, request) => {
        // let { workspaceId } = request.params;
        let body = JSON.parse(request.requestBody);
        // let activeWorkspace = schema.db.myWorkspaces[0].content.find(t => t.id === workspaceId);
        let workspace = schema.db.workspace[0];
        let activeUsers = workspace.users.filter((user) => body.includes(user.id));
        workspace.update({ users: activeUsers });
        return workspace;
      });

      this.put(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), (schema, request) => {
        // let { workspaceId } = request.params;
        // let body = JSON.parse(request.requestBody);
        // let activeWorkspace = schema.db.myWorkspaces[0].content.find(t => t.id === workspaceId);
        return schema.db.workspace[0];
      });

      this.patch(serviceUrl.getManageWorkspaceLabels({ workspace: ":workspace" }), (schema, request) => {
        // let { workspaceId } = request.params;
        let body = JSON.parse(request.requestBody);
        // let activeWorkspace = schema.db.myWorkspaces[0].content.find(t => t.id === workspaceId);
        let workspace = schema.db.workspace[0];
        workspace.update({ labels: body });
        return workspace;
      });

      this.post(serviceUrl.getManageWorkspacesCreate(), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        const workspaces = schema.workspaces.first();
        const updatedRecords = workspaces.records.concat({ id: uuid(), isActive: true, ...body });
        workspaces.update({ records: updatedRecords });
        return {};
      });

      /**
       * Manage Users
       */

      this.get(serviceUrl.getUsers({ query: null }), (schema, request) => {
        const { query } = request.queryParams;
        const userData = schema.db.users[0];
        if (query) {
          userData.content =
            userData.content.filter((user) => user.name.includes(query) || user.email.includes(query)) ?? [];
        }
        return userData;
      });

      this.get(serviceUrl.getUser({ userId: ":userId" }), (schema, request) => {
        const { userId } = request.params;
        const user = schema.db.users[0].content.find((user) => user.id === userId);
        return user;
      });

      this.patch(serviceUrl.getUser({ userId: ":userId" }), (schema, request) => {
        const { userId } = request.params;
        let body = JSON.parse(request.requestBody);
        const users = schema.users.first();
        const updatedRecords = [];
        for (let user of users.records) {
          if (user.id === userId) {
            user = user = { ...user, ...body };
          }
          updatedRecords.push(user);
        }
        users.update({ records: updatedRecords });
        return {};
      });

      this.get(serviceUrl.getWorkspaceQuotaDefaults(), (schema, request) => {
        return {
          maxWorkflowCount: 20,
          maxWorkflowRunMonthly: 150,
          maxWorkflowStorage: 10,
          maxWorkflowRunDuration: 30,
          maxConcurrentRuns: 4,
        };
      });

      /**
       *  Manage Settings
       * */

      this.get(serviceUrl.resourceSettings(), (schema) => {
        return schema.settings.all();
      });

      this.put(serviceUrl.resourceSettings(), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        const settings = schema.settings.all();
        settings.update(body[0]);
        return schema.settings.all();
      });

      /**
       * Manage and Administer Tokens
       */
      this.get(serviceUrl.getTokens({ query: null }), (schema) => {
        return schema.db.tokens[0];
      });

      this.get(serviceUrl.getGlobalTokens(), (schema) => {
        return schema.db.tokens;
      });

      this.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), (schema, request) => {
        return {};
      });

      this.post(serviceUrl.postToken(), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        let newToken = {
          ...body,
          creatorId: "1",
          creationDate: Date.now(),
          principal: "123124314123123",
          token: "bft_12341241432321321",
        };
        return schema.tokens.create(newToken);
      });

      /**
       * Integrations
       */
      this.get(serviceUrl.getIntegrations({ workspace: null }), (schema, request) => {
        return schema.db.integrations;
      });

      this.get(serviceUrl.getGitHubAppInstallation({ id: null }), (schema, request) => {
        return schema.db.installations[0];
      });

      this.get(serviceUrl.getGitHubAppInstallationForWorkspace({ workspace: null }), (schema, request) => {
        return schema.db.installations[0];
      });
    },
  });
}
