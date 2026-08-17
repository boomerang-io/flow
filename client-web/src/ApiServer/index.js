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
      manageTeam: Model,
      manageUser: Model,
      quotas: Model,
      revision: Model,
      setting: Model,
      summary: Model,
      task: Model,
      taskYaml: Model,
      team: Model,
      teamApproverUsers: Model,
      teamNameValidate: Model,
      teamProperties: Model,
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

      // this.get(serviceUrl.getMyTeams({ query: null }), (schema) => {
      //   return schema.db.myTeams[0];
      // });

      this.get(serviceUrl.getFeatureFlags(), (schema) => {
        return schema.db.featureFlags[0];
      });

      this.get(serviceUrl.team.schedule.getSchedule({ team: ":team", query: null }), (schema) => {
        return schema.db.workflowSchedules;
      });

      this.get(serviceUrl.team.schedule.getSchedules({ team: ":team", query: null }), (schema) => {
        return schema.db.workflowSchedules[0];
      });

      this.get(serviceUrl.team.workflow.getAvailableParameters({ team: ":team", name: ":name" }), (schema) => {
        return schema.db.availableParameters[0].data;
      });

      this.get(serviceUrl.template.getWorkflowTemplates(), (schema) => {
        return schema.db.workflowTemplates[0];
      });

      this.get(serviceUrl.team.workflow.getWorkflows({ team: null, query: null }), (schema) => {
        return schema.db.workflows[0];
      });

      // this.get(serviceUrl.getTeamQuotas({ id: ":id" }), (schema) => {
      //   return schema.db.quotas[0];
      // });

      this.get(serviceUrl.getTeams({ query: null }), (schema) => {
        return schema.db.teams[0];
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
       * Team Propertiies
       */
      this.get(serviceUrl.team.resourceTeamParameters({ team: ":team" }), (schema, request) => {
        let { team } = request.params;
        let property = schema.teamProperties.find(team);
        return property && property.properties ? property.properties : [];
      });
      this.post(serviceUrl.team.resourceTeamParameters({ team: ":team" }), (schema, request) => {
        /**
         * find team record, update the list of properties for that team
         */
        let { team } = request.params;
        let body = JSON.parse(request.requestBody);
        let activeTeamProperty = schema.teamProperties.find(team);
        let currentProperties = activeTeamProperty.attrs.properties;
        currentProperties.push({ id: uuid(), ...body });
        activeTeamProperty.update({ properties: currentProperties });
        return schema.teamProperties.all();
      });
      this.patch(
        serviceUrl.team.resourceTeamParameters({ team: ":team", configurationId: ":configurationId" }),
        (schema, request) => {
          /**
           * find team record, update the list of properties for that team
           */
          let { team, configurationId } = request.params;
          let body = JSON.parse(request.requestBody);
          let activeTeamProperty = schema.teamProperties.find(team);
          let currentProperties = activeTeamProperty.attrs.properties;
          let foundIndex = currentProperties.findIndex((prop) => prop.id === configurationId);
          currentProperties[foundIndex] = body;
          activeTeamProperty.update({ properties: currentProperties });
          return schema.teamProperties.all();
        },
      );
      this.delete(
        serviceUrl.team.resourceTeamParameters({ team: ":team", configurationId: ":configurationId" }),
        (schema, request) => {
          /**
           * find team record, update the list of properties for that team
           */
          let { team, configurationId } = request.params;
          let activeTeamProperty = schema.teamProperties.find(team);
          let currentProperties = activeTeamProperty.attrs.properties;
          let newProperties = currentProperties.filter((prop) => prop.id !== configurationId);
          activeTeamProperty.update({ properties: newProperties });
          return schema.teamProperties.all();
        },
      );

      /**
       * Insights
       */
      this.get(serviceUrl.team.getInsights({ team: ":team", query: null }), (schema, request) => {
        //grab the querystring from the end of the request url
        const query = request.url.substring(14);
        // eslint-disable-next-line
        const { fromDate = null, toDate = null, team = null } = queryString.parse(query);
        const activeTeam = team && schema.db.myTeams.find(team);
        let activeExecutions = activeTeam && schema.db.insights[0].executions.filter((t) => t.name === team.name);
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
      this.put(serviceUrl.task.putTask({ replace: "true", team: ":team" }), (schema, request) => {
        return {};
      });

      /**
       * Workflows
       */
      this.post(serviceUrl.team.workflow.postCreateWorkflow({ team: ":team" }), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        let workflow = { ...body, id: uuid(), createdDate: Date.now(), revisionCount: 1, status: "active" };
        if (body.flowTeamId) {
          let flowTeam = schema.myTeams.findBy({ id: body.flowTeamId });
          const teamWorkflows = [...flowTeam.workflows];
          teamWorkflows.push(workflow);
          flowTeam.update({ workflows: teamWorkflows });
          return schema.summaries.create(workflow);
        }
        return {};
      });

      this.get(
        serviceUrl.team.workflow.getWorkflowCompose({ team: ":team", name: ":name", version: null }),
        (schema, request) => {
          let { id } = request.params;
          return schema.db.workflowCompose.findBy({ id });
        },
      );

      this.del(serviceUrl.team.workflow.getWorkflow({ team: ":team", name: ":name" }), (schema, request) => {
        let { name } = request.params;
        let flowTeam = schema.myTeams.where((team) => team.workflows.find((workflow) => workflow.name === name));
        let { attrs } = flowTeam.models[0];
        const teamWorkflows = attrs.workflows.filter((workflow) => workflow.name !== name);
        flowTeam.update({ workflows: teamWorkflows });
        return schema.db.summaries.remove({ name: name });
      });

      //Workflow Config Cron

      this.get(`${BASE_URL}/workflow/validate/cron`, () => {
        return {
          valid: true,
        };
      });

      // Workflow Changelog
      this.get(serviceUrl.team.workflow.getWorkflowChangelog({ team: ":team", name: ":name" }), (schema, request) => {
        return schema.db.changelogs;
      });

      /**
       * Activity
       */
      this.get(serviceUrl.team.workflowrun.getWorkflowRuns({ team: ":team", query: null }), (schema) => {
        return schema.db.workflowRuns[0];
      });

      this.get(serviceUrl.team.workflowrun.getWorkflowRunCount({ team: ":team", query: null }), (schema, request) => {
        return schema.db.workflowRunCount[0];
      });

      this.get(serviceUrl.team.workflowrun.getWorkflowRun({ team: ":team", id: ":id" }), (schema, request) => {
        return schema.db.workflowExecution[0];
      });

      this.post(
        serviceUrl.team.workflow.postSubmitWorkflow({ team: ":team", name: ":name", body: null }),
        (schema, request) => {
          return schema.db.workflowExecution[0];
        },
      );

      this.delete(
        serviceUrl.team.workflowrun.deleteCancelWorkflow({ team: ":team", runId: ":id" }),
        (schema, request) => {
          return {};
        },
      );

      /**
       * Actions
       */
      this.get(serviceUrl.team.action.getActionsSummary({ team: ":team", query: null }), (schema) => {
        return schema.db.actionsSummary[0];
      });

      this.get(serviceUrl.team.action.getActions({ team: ":team", query: null }), (schema, request) => {
        const { type } = request.queryParams;
        if (type === "approval") return schema.db.approvals[0];
        if (type === "task") return schema.db.manualTasks[0];
        return {};
      });

      this.put(serviceUrl.team.action.putAction(), () => {
        return {};
      });

      /**
       * Approvers Group
       */
      this.get(serviceUrl.resourceApproverGroups({ team: ":team" }), (schema) => {
        return schema.db.approverGroups;
      });

      //Delete approver group
      this.delete(serviceUrl.resourceApproverGroups({ team: ":team", groupId: ":groupId" }), (schema, request) => {
        const { groupId } = request.params;
        const approverGroup = schema.approverGroups.find(groupId);
        approverGroup.destroy();
      });

      //Create approver group
      this.post(serviceUrl.resourceApproverGroups({ team: ":team" }), (schema, request) => {
        const body = JSON.parse(request.requestBody);
        schema.approverGroups.create({ groupId: uuid(), ...body });
        return schema.approverGroups.all();
      });

      //Update approver group
      this.put(serviceUrl.resourceApproverGroups({ team: ":team" }), (schema, request) => {
        return {};
      });

      /**
       * Manage Team
       */

      this.post(serviceUrl.postTeamValidateName(), (schema, request) => {
        return new Response(422, {}, { errors: ["Name is already taken"] });
      });

      this.get(serviceUrl.resourceTeam({ team: ":team" }), (schema, request) => {
        // let { teamId } = request.params;
        return schema.db.team[0];
      });

      this.patch(serviceUrl.resourceTeam({ team: ":team" }), (schema, request) => {
        // let { teamId } = request.params;
        let body = JSON.parse(request.requestBody);
        // let activeTeam = schema.db.myTeams[0].content.find(t => t.id === teamId);
        let team = schema.db.team[0];
        let activeUsers = team.users.filter((user) => body.includes(user.id));
        team.update({ users: activeUsers });
        return team;
      });

      this.put(serviceUrl.resourceTeam({ team: ":team" }), (schema, request) => {
        // let { teamId } = request.params;
        // let body = JSON.parse(request.requestBody);
        // let activeTeam = schema.db.myTeams[0].content.find(t => t.id === teamId);
        return schema.db.team[0];
      });

      this.patch(serviceUrl.getManageTeamLabels({ team: ":team" }), (schema, request) => {
        // let { teamId } = request.params;
        let body = JSON.parse(request.requestBody);
        // let activeTeam = schema.db.myTeams[0].content.find(t => t.id === teamId);
        let team = schema.db.team[0];
        team.update({ labels: body });
        return team;
      });

      this.post(serviceUrl.getManageTeamsCreate(), (schema, request) => {
        let body = JSON.parse(request.requestBody);
        const teams = schema.teams.first();
        const updatedRecords = teams.records.concat({ id: uuid(), isActive: true, ...body });
        teams.update({ records: updatedRecords });
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

      this.get(serviceUrl.getTeamQuotaDefaults(), (schema, request) => {
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
      this.get(serviceUrl.getIntegrations({ team: null }), (schema, request) => {
        return schema.db.integrations;
      });

      this.get(serviceUrl.getGitHubAppInstallation({ id: null }), (schema, request) => {
        return schema.db.installations[0];
      });

      this.get(serviceUrl.getGitHubAppInstallationForTeam({ team: null }), (schema, request) => {
        return schema.db.installations[0];
      });
    },
  });
}
