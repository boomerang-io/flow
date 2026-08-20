// MSW handler layer, authored alongside the existing Mirage server (src/ApiServer/index.js)
// rather than replacing it - see the migration note in the PR/commit description for what the
// eventual switchover involves. Covers the same route surface Mirage serves, reusing the same
// fixtures (src/ApiServer/fixtures), but is not wired into any spec yet.
//
// Route patterns are built with the app's own `serviceUrl` builders (Config/servicesConfig) -
// called here with the *correct* argument names and `:param` tokens - instead of hand-written
// path strings. That's a deliberate difference from src/ApiServer/index.js: several of its routes
// were registered by calling a serviceUrl builder with the wrong key (e.g. `name` where the
// builder expects `workflow`), which silently produces the literal path segment "undefined" and
// a route that can never match a real request. Driving the pattern off the same builder the real
// app code calls removes that whole bug class - if the real call site and the mock registration
// ever drift, they drift through the same function.
//
// MSW matches routes on pathname only; any `?query=` a serviceUrl builder appends for an
// optional/list argument is irrelevant to matching; and the query is used directly in the
// matched requests
import { http, HttpResponse, type HttpHandler } from "msw";
import { serviceUrl, BASE_URL } from "Config/servicesConfig";
import * as fixtures from "ApiServer/fixtures";
import { db } from "./db";

// `Request.json()` is typed `Promise<any>` by the DOM lib (there's no way to know the shape of
// an arbitrary request body), so returning it from a function declared to return
// `Promise<Record<string, unknown>>` type-checks without a cast - `any` is assignable to
// anything. Declaring the parameter as the plain DOM `Request` type (rather than letting it
// infer MSW's own `StrictRequest<DefaultBodyType>`) is what makes `.json()` resolve to `any`
// here instead of MSW's narrower `DefaultBodyType`.
function jsonBody(request: Request): Promise<Record<string, unknown>> {
  return request.json();
}

// Same trick as jsonBody, for the one handler whose body is an array rather than an object -
// narrowed with a runtime filter instead of trusting the shape, since nothing enforces it.
async function jsonStringArrayBody(request: Request): Promise<string[]> {
  const body: unknown = await request.json();
  return Array.isArray(body) ? body.filter((item): item is string => typeof item === "string") : [];
}

// MSW path params are typed `string | readonly string[] | undefined` (a param can, in
// principle, capture a repeated segment or be missing) - every route here uses single named
// segments, so narrow with a runtime check instead of an `as` cast.
function pathParam(value: string | readonly string[] | undefined): string {
  if (typeof value !== "string") {
    throw new Error("expected a single-segment path parameter");
  }
  return value;
}

export const handlers: HttpHandler[] = [
  /**
   * Simple GET of static data
   */
  http.get(`${BASE_URL}/info`, () => HttpResponse.json([])),
  http.get(serviceUrl.getUserProfile(), () => HttpResponse.json(fixtures.profile)),
  http.get(serviceUrl.getContext(), () => HttpResponse.json(fixtures.platformConfig)),
  http.get(`${BASE_URL}/navigation`, () => HttpResponse.json(fixtures.flowNavigation)),
  http.get(serviceUrl.getFeatureFlags(), () => HttpResponse.json(fixtures.featureFlags)),
  http.get(serviceUrl.template.getWorkflowTemplates(), () => HttpResponse.json(fixtures.workflowTemplates)),
  http.get(serviceUrl.getWorkspaceQuotaDefaults(), () =>
    HttpResponse.json({
      maxWorkflowCount: 20,
      maxWorkflowRunMonthly: 150,
      maxWorkflowStorage: 10,
      maxWorkflowRunDuration: 30,
      maxConcurrentRuns: 4,
    }),
  ),

  http.put(serviceUrl.putActivationApp(), () => HttpResponse.json({})),

  /**
   * Global Parameters
   */
  http.get(serviceUrl.getGlobalParameters(), () => HttpResponse.json(db.globalParams)),
  http.post(serviceUrl.getGlobalParameters(), async ({ request }) => {
    const body = await jsonBody(request);
    const created = { id: crypto.randomUUID(), ...body };
    db.globalParams.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),
  // Global parameters are updated in bulk via PUT /parameters (no id in the path); the request
  // body carries the full parameter (see resolver.patchGlobalParameter).
  http.put(serviceUrl.getGlobalParameters(), async ({ request }) => {
    const body = await jsonBody(request);
    const index = db.globalParams.findIndex((param) => param.name === body.name);
    if (index === -1) return HttpResponse.json({ errors: ["Parameter not found"] }, { status: 404 });
    db.globalParams[index] = { ...db.globalParams[index], ...body };
    return HttpResponse.json(db.globalParams[index]);
  }),
  http.delete(serviceUrl.getGlobalParameter({ name: ":name" }), ({ params }) => {
    const name = pathParam(params.name);
    db.globalParams = db.globalParams.filter((param) => param.name !== name);
    return HttpResponse.json({});
  }),

  /**
   * Workspace Properties
   *
   * Divergence: real backend has no per-property PATCH/DELETE-by-configurationId sub-route (see
   * servicesConfig.ts's TODO on resourceWorkspaceParameters) - updates go through the workspace
   * PATCH below and per-name deletes through workspace.deleteWorkspaceParameter. Mirage mocked a
   * `configurationId` path segment that the underlying serviceUrl builder silently drops, so
   * those two handlers never actually matched a real-shaped request; not ported.
   */
  http.get(serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace" }), ({ params }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    return HttpResponse.json(workspace?.parameters ?? []);
  }),
  http.post(serviceUrl.workspace.resourceWorkspaceParameters({ workspace: ":workspace" }), async ({ params, request }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    if (!workspace) return HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 });
    const body = await jsonBody(request);
    workspace.parameters = [...(workspace.parameters ?? []), { id: crypto.randomUUID(), ...body }];
    return HttpResponse.json(workspace.parameters);
  }),
  http.delete(serviceUrl.workspace.deleteWorkspaceParameter({ workspace: ":workspace", name: ":name" }), ({ params }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    if (!workspace) return HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 });
    workspace.parameters = (workspace.parameters ?? []).filter((property) => property.key !== params.name);
    return HttpResponse.json({});
  }),

  /**
   * Insights
   */
  http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), () => HttpResponse.json(fixtures.insights)),

  /**
   * Tasks
   */
  // task.queryTasks (a literal /task/query) must be registered before task.getTask (the
  // parameterized /task/:name below) - MSW matches handlers in registration order with no
  // literal-over-param precedence, so a /task/:name registered first would swallow "query" as a
  // task name and 404 instead of ever reaching the list handler. Same reasoning applies to every
  // other literal-vs-:param pair at the same path depth in this file (see getWorkspaces vs
  // resourceWorkspace and getSchedulesCalendars vs getSchedule further down).
  http.get(serviceUrl.task.queryTasks({ query: "" }), () => HttpResponse.json({ content: db.tasks })),
  http.get(serviceUrl.task.getTask({ name: ":name" }), ({ params, request }) => {
    if (request.headers.get("Accept") === "application/x-yaml") {
      return new HttpResponse(fixtures.taskYaml[0].yaml, { headers: { "content-type": "application/x-yaml" } });
    }
    const task = db.tasks.find((t) => t.name === params.name);
    return task ? HttpResponse.json(task) : HttpResponse.json({ errors: ["Task not found"] }, { status: 404 });
  }),
  http.get(serviceUrl.task.getTaskChangelog({ name: ":name" }), () => HttpResponse.json(fixtures.changelogs)),
  // Reflects the current name-collision-only contract (see the workspace validate-name handler
  // below): the previous Mirage handler unconditionally returned a canned "Name is already
  // taken" error with a 200 status, which would fail every submission regardless of input.
  http.post(serviceUrl.task.postValidateYaml(), () => HttpResponse.json({})),
  // putTask always appends "?replace=..." unconditionally (see servicesConfig.ts), so the raw
  // builder output isn't a clean path pattern; drop the query MSW would otherwise warn about
  // matching against literally, since path matching should ignore it anyway.
  http.put(serviceUrl.task.putTask({ name: ":name", replace: false }).split("?")[0], async ({ params, request }) => {
    const name = pathParam(params.name);
    const body = await jsonBody(request);
    const index = db.tasks.findIndex((task) => task.name === name);
    const updated = { ...(index === -1 ? {} : db.tasks[index]), ...body, name };
    if (index === -1) db.tasks.push(updated);
    else db.tasks[index] = updated;
    return HttpResponse.json(updated);
  }),

  /**
   * Workflows
   */
  http.post(serviceUrl.workspace.workflow.postCreateWorkflow({ workspace: ":workspace" }), async ({ request }) => {
    const body = await jsonBody(request);
    const workflow = { ...body, id: crypto.randomUUID(), createdDate: Date.now(), revisionCount: 1, status: "active" };
    db.workflows.push(workflow);
    return HttpResponse.json(workflow, { status: 201 });
  }),
  http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), () => HttpResponse.json({ content: db.workflows })),
  http.get(serviceUrl.workspace.workflow.getWorkflow({ workspace: ":workspace", workflow: ":workflow" }), ({ params }) => {
    const workflow = findWorkflow(pathParam(params.workflow));
    return workflow ? HttpResponse.json(workflow) : HttpResponse.json({ errors: ["Workflow not found"] }, { status: 404 });
  }),
  http.delete(serviceUrl.workspace.workflow.getWorkflow({ workspace: ":workspace", workflow: ":workflow" }), ({ params }) => {
    db.workflows = db.workflows.filter((workflow) => workflow.name !== params.workflow && workflow.id !== params.workflow);
    return HttpResponse.json({});
  }),
  http.get(
    serviceUrl.workspace.workflow.getWorkflowCompose({ workspace: ":workspace", workflow: ":workflow" }),
    ({ params }) => {
      const compose =
        fixtures.workflowCompose.find((wf) => wf.id === params.workflow || wf.name === params.workflow) ??
        fixtures.workflowCompose[0];
      return HttpResponse.json(compose);
    },
  ),
  http.put(
    serviceUrl.workspace.workflow.putApplyWorkflowCompose({ workspace: ":workspace", workflow: ":workflow" }),
    async ({ request }) => HttpResponse.json(await jsonBody(request)),
  ),
  http.get(
    serviceUrl.workspace.workflow.getWorkflowChangelog({ workspace: ":workspace", workflow: ":workflow" }),
    () => HttpResponse.json(fixtures.changelogs),
  ),
  http.get(
    serviceUrl.workspace.workflow.getAvailableParameters({ workspace: ":workspace", workflow: ":workflow" }),
    () => HttpResponse.json(fixtures.availableParameters.data),
  ),
  http.post(
    serviceUrl.workspace.workflow.postSubmitWorkflow({ workspace: ":workspace", workflow: ":workflow" }),
    () => HttpResponse.json(fixtures.workflowExecution),
  ),
  // The real endpoint has no dedicated workflow-level validate-name route (see
  // servicesConfig.ts's TODO on workspace.workflow.postValidateName) - Mirage never mocked one
  // either, so this stays unmocked here too.
  //
  // getCronValidation always appends "?cron=..." unconditionally, same as putTask's "?replace="
  // above - strip it for the same reason (a clean path pattern; the query has no bearing on
  // matching).
  http.get(serviceUrl.schedule.getCronValidation({ workspace: ":workspace", expression: ":expression" }).split("?")[0], () =>
    HttpResponse.json({ valid: true }),
  ),

  /**
   * Workflow Runs
   */
  http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () =>
    HttpResponse.json(fixtures.workflowRuns),
  ),
  http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), () =>
    HttpResponse.json(fixtures.workflowRunCount),
  ),
  http.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: ":workspace", id: ":id" }), () =>
    HttpResponse.json(fixtures.workflowExecution),
  ),
  http.delete(serviceUrl.workspace.workflowrun.deleteCancelWorkflow({ workspace: ":workspace", id: ":id" }), () =>
    HttpResponse.json({}),
  ),

  /**
   * Actions
   */
  http.get(serviceUrl.workspace.action.getActionsSummary({ workspace: ":workspace" }), () =>
    HttpResponse.json(fixtures.actionsSummary),
  ),
  http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), ({ request }) => {
    const type = new URL(request.url).searchParams.get("type");
    if (type === "approval") return HttpResponse.json(fixtures.approvals);
    if (type === "task") return HttpResponse.json(fixtures.manualTasks);
    return HttpResponse.json({});
  }),
  http.put(serviceUrl.workspace.action.putAction({ workspace: ":workspace" }), () => HttpResponse.json({})),

  /**
   * Approver Groups
   */
  http.get(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), () => HttpResponse.json(db.approverGroups)),
  http.post(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), async ({ request }) => {
    const body = await jsonBody(request);
    db.approverGroups.push({ id: crypto.randomUUID(), ...body });
    return HttpResponse.json(db.approverGroups);
  }),
  // Real contract (resolver.deleteApproverGroup): DELETE with no id in the path, body is an
  // array of group names to remove. Mirage instead registered a `/:groupId` path segment that
  // the underlying serviceUrl.resourceApproverGroups builder ignores (it only takes `workspace`),
  // so that path segment was dead and the handler's `request.params.groupId` read was always
  // undefined.
  http.delete(serviceUrl.resourceApproverGroups({ workspace: ":workspace" }), async ({ request }) => {
    const names = await jsonStringArrayBody(request);
    db.approverGroups = db.approverGroups.filter((group) => !names.includes(group.name ?? ""));
    return HttpResponse.json({});
  }),

  /**
   * Manage Workspaces
   */
  http.post(serviceUrl.postWorkspaceValidateName(), async ({ request }) => {
    const { name } = await jsonBody(request);
    if (db.workspaces.some((workspace) => workspace.name === name)) {
      return HttpResponse.json({ errors: ["Name is already taken"] }, { status: 422 });
    }
    return HttpResponse.json({});
  }),
  // getWorkspaces (the literal /workspace/query list route) must be registered before
  // resourceWorkspace's GET (the parameterized /workspace/:workspace below) - see the ordering
  // note on task.queryTasks above.
  http.get(serviceUrl.getWorkspaces({ query: "" }), () => HttpResponse.json({ content: db.workspaces })),
  http.get(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), ({ params }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    return workspace ? HttpResponse.json(workspace) : HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 });
  }),
  http.patch(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), async ({ params, request }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    if (!workspace) return HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 });
    Object.assign(workspace, await jsonBody(request));
    return HttpResponse.json(workspace);
  }),
  http.patch(serviceUrl.getManageWorkspaceLabels({ workspace: ":workspace" }), async ({ params, request }) => {
    const workspace = findWorkspace(pathParam(params.workspace));
    if (!workspace) return HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 });
    workspace.labels = await jsonBody(request);
    return HttpResponse.json(workspace);
  }),
  http.post(serviceUrl.getManageWorkspacesCreate(), async ({ request }) => {
    const body = await jsonBody(request);
    const created = {
      id: crypto.randomUUID(),
      status: "active",
      creationDate: new Date().toISOString(),
      members: [],
      quotas: fixtures.quotas,
      ...body,
    };
    db.workspaces.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  /**
   * Schedules
   */
  http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () =>
    HttpResponse.json({ content: db.schedules }),
  ),
  // Literal /schedule/calendars must precede the parameterized /schedule/:id below - same
  // ordering hazard as task.queryTasks/getWorkspaces above.
  http.get(serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace: ":workspace" }), () =>
    HttpResponse.json(fixtures.workflowCalendar),
  ),
  http.get(serviceUrl.workspace.schedule.getSchedule({ workspace: ":workspace", id: ":id" }), ({ params }) => {
    const schedule = db.schedules.find((s) => s.id === params.id);
    return schedule ? HttpResponse.json(schedule) : HttpResponse.json({ errors: ["Schedule not found"] }, { status: 404 });
  }),
  http.delete(serviceUrl.workspace.schedule.deleteSchedule({ workspace: ":workspace", id: ":id" }), ({ params }) => {
    db.schedules = db.schedules.filter((s) => s.id !== params.id);
    return HttpResponse.json({});
  }),

  /**
   * Manage Users
   */
  http.get(serviceUrl.getUsers({ query: "" }), ({ request }) => {
    const query = new URL(request.url).searchParams.get("query");
    const content = query
      ? db.users.filter((user) => user.name?.includes(query) || user.email?.includes(query))
      : db.users;
    return HttpResponse.json({ content });
  }),
  http.get(serviceUrl.getUser({ userId: ":userId" }), ({ params }) => {
    const user = db.users.find((u) => u.id === params.userId);
    return user ? HttpResponse.json(user) : HttpResponse.json({ errors: ["User not found"] }, { status: 404 });
  }),
  http.patch(serviceUrl.getUser({ userId: ":userId" }), async ({ params, request }) => {
    const index = db.users.findIndex((u) => u.id === params.userId);
    if (index === -1) return HttpResponse.json({ errors: ["User not found"] }, { status: 404 });
    db.users[index] = { ...db.users[index], ...(await jsonBody(request)) };
    return HttpResponse.json(db.users[index]);
  }),

  /**
   * Manage Settings
   */
  http.get(serviceUrl.resourceSettings(), () => HttpResponse.json(db.settings)),
  http.put(serviceUrl.resourceSettings(), async ({ request }) => {
    const body = await jsonBody(request);
    db.settings[0] = { ...db.settings[0], ...body };
    return HttpResponse.json(db.settings);
  }),

  /**
   * Tokens
   *
   * getTokens and getGlobalTokens share the same pathname (/token/query) - the real distinction
   * is the `types=global` query param, not the path - so one handler serves both, matching the
   * flavor the query string asks for instead of registering two routes MSW would resolve to the
   * same pattern anyway.
   */
  http.get(serviceUrl.getTokens({ query: "" }), ({ request }) => {
    const isGlobal = new URL(request.url).searchParams.get("types") === "global";
    return isGlobal ? HttpResponse.json(db.tokens) : HttpResponse.json({ content: db.tokens });
  }),
  http.get(serviceUrl.getTokenCatalog({ query: "" }), () => HttpResponse.json(fixtures.tokenCatalog)),
  http.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), ({ params }) => {
    db.tokens = db.tokens.filter((token) => token.id !== params.tokenId);
    return HttpResponse.json({});
  }),
  http.post(serviceUrl.postToken(), async ({ request }) => {
    const body = await jsonBody(request);
    const created = {
      ...body,
      id: crypto.randomUUID(),
      creatorId: "1",
      creationDate: Date.now(),
      principal: "123124314123123",
      token: "bft_12341241432321321",
    };
    db.tokens.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  /**
   * Integrations
   *
   * getGitHubAppInstallation and getGitHubAppInstallationForWorkspace also share one pathname
   * (/integration/github/installation) for the same reason as the token routes above.
   */
  http.get(serviceUrl.getIntegrations({ workspace: "" }), () => HttpResponse.json(fixtures.integrations)),
  http.get(serviceUrl.getGitHubAppInstallation({ id: "" }), () => HttpResponse.json(fixtures.installations)),
];

function findWorkspace(identifier: string) {
  return db.workspaces.find((workspace) => workspace.name === identifier || workspace.id === identifier);
}

function findWorkflow(identifier: string) {
  return db.workflows.find((workflow) => workflow.name === identifier || workflow.id === identifier);
}
