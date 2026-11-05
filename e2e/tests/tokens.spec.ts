import { expect, type APIRequestContext } from "@playwright/test";
import {
  API_ORIGIN,
  APP_BASENAME,
  createWorkflow,
  createWorkspaceToken,
  deleteToken,
  getWorkspaceTokens,
  uniqueName,
  workspaceTest,
  type RestErrorResponse,
} from "../support/api";
import { setWorkflowTriggers, type WorkflowRunView } from "../support/dispatcher";

/*
 * A workspace token driving runs through the API, and the trigger a submit records. The caller
 * decides the trigger: `manual` for a person signed in to the webapp, `api` for a token. Both
 * answer to the workflow's Manual switch, and a body may name only those two (in any case). The
 * token holds one grant, workflow/action, so anything else it asks for is a 403 in the platform's
 * error shape. Every test owns its workspace, workflow and token; the token is deleted when the
 * test ends. Submits use start=false, so no dispatcher is needed.
 */

type TokenFixtures = {
  workflow: { name: string; displayName: string };
  token: { id: string; token: string };
  // Authenticated by the token alone: no session cookie.
  tokenApi: APIRequestContext;
};

const test = workspaceTest("e2e-tokens").extend<TokenFixtures>({
  workflow: async ({ request, workspace }, use) => {
    await use(await createWorkflow(request, workspace, uniqueName("e2e-token-wf")));
  },
  token: async ({ request, workspace }, use) => {
    const created = await createWorkspaceToken(request, workspace, uniqueName("e2e-token"), ["workflow/action"]);
    await use(created);
    if ((await getWorkspaceTokens(request, workspace)).some((t) => t.id === created.id)) {
      await deleteToken(request, created.id);
    }
  },
  tokenApi: async ({ playwright, token }, use) => {
    const context = await playwright.request.newContext({
      storageState: { cookies: [], origins: [] },
      extraHTTPHeaders: { Authorization: `Bearer ${token.token}` },
    });
    await use(context);
    await context.dispose();
  },
});

const submitUrl = (workspace: string, workflow: string) =>
  `${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${workflow}/submit?start=false`;

test("a token's submit records the api trigger, shown as API in Activity", async ({
  page,
  workspace,
  workflow,
  tokenApi,
}) => {
  const res = await tokenApi.post(submitUrl(workspace, workflow.name), { data: {} });
  expect(res.status(), await res.text()).toBe(200);
  const run = (await res.json()) as WorkflowRunView;
  expect(run.trigger).toBe("api");

  await page.goto(`${APP_BASENAME}/${workspace}/activity`);
  const row = page.getByTestId("configuration-property-table-row").filter({ hasText: workflow.displayName });
  await expect(row).toBeVisible();
  await expect(row.getByText("API", { exact: true })).toBeVisible();
});

test("a token is refused an action it does not hold with a 403 error body", async ({
  workspace,
  workflow,
  tokenApi,
}) => {
  // Reading the workflow needs workflow/read, which the token was not given.
  const res = await tokenApi.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${workflow.name}`);
  expect(res.status()).toBe(403);
  const body = (await res.json()) as RestErrorResponse;
  expect(body).toMatchObject({ code: 403, reason: "PERMISSION_DENIED", status: "403 FORBIDDEN" });
  expect(body.message).toBeTruthy();
});

test("with the Manual switch off, the webapp's Run it is disabled", async ({ page, request, workspace, workflow }) => {
  await setWorkflowTriggers(request, workspace, workflow.name, { manual: { enabled: false } });

  await page.goto(`${APP_BASENAME}/${workspace}/workflows`);
  await expect(page.getByText(workflow.displayName)).toBeVisible();
  await expect(page.getByRole("button", { name: "Run it" }).first()).toBeDisabled();
});

test("with the Manual switch off, a token's submit and a session's submit are refused", async ({
  request,
  workspace,
  workflow,
  tokenApi,
}) => {
  await setWorkflowTriggers(request, workspace, workflow.name, { manual: { enabled: false } });

  for (const [caller, api] of [
    ["token", tokenApi],
    ["session", request],
  ] as const) {
    const res = await api.post(submitUrl(workspace, workflow.name), { data: {} });
    expect(res.status(), `${caller}: ${await res.text()}`).toBe(400);
    expect(((await res.json()) as RestErrorResponse).reason, caller).toBe("WORKFLOWRUN_TRIGGER_DISABLED");
  }
});

test("a body trigger is read in any case, and one other than manual or api is refused", async ({
  request,
  workspace,
  workflow,
}) => {
  const manual = await request.post(submitUrl(workspace, workflow.name), { data: { trigger: "Manual" } });
  expect(manual.status(), await manual.text()).toBe(200);
  expect(((await manual.json()) as WorkflowRunView).trigger).toBe("manual");

  const schedule = await request.post(submitUrl(workspace, workflow.name), { data: { trigger: "schedule" } });
  expect(schedule.status(), await schedule.text()).toBe(400);
  expect(((await schedule.json()) as RestErrorResponse).reason).toBe("WORKFLOWRUN_INVALID_TRIGGER");
});

test("a token records when it was last used", async ({ request, workspace, workflow, token, tokenApi }) => {
  const before = (await getWorkspaceTokens(request, workspace)).find((t) => t.id === token.id);
  expect(before?.lastUsedAt ?? null).toBeNull();

  const usedAt = Date.now();
  const res = await tokenApi.post(submitUrl(workspace, workflow.name), { data: {} });
  expect(res.status(), await res.text()).toBe(200);

  const after = (await getWorkspaceTokens(request, workspace)).find((t) => t.id === token.id);
  expect(after?.lastUsedAt, "lastUsedAt is set by the token's first request").toBeTruthy();
  // Within a minute of the request, allowing for clock skew between this host and the stack.
  expect(Math.abs(Date.parse(String(after?.lastUsedAt)) - usedAt)).toBeLessThan(60_000);
});

test("a deleted token no longer authenticates", async ({ request, workspace, workflow, token, tokenApi }) => {
  await deleteToken(request, token.id);
  expect((await getWorkspaceTokens(request, workspace)).map((t) => t.id)).not.toContain(token.id);

  const res = await tokenApi.post(submitUrl(workspace, workflow.name), { data: {} });
  expect(res.status()).toBe(401);
});
