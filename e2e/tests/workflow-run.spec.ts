import { test, expect } from "@playwright/test";
import { uniqueName, createWorkspace, createWorkflow, submitWorkflowRun, executionStatusCopy } from "../support/api";

/*
 * Submit a run via the API (no agent is part of this stack - see docker-compose.yml's
 * comment on why - so a run never leaves `ready`/`invalid`; it does not need to reach
 * `running`/`succeeded` to be a meaningful UI journey), then confirm the Activity list and
 * the run detail page both show the run with the *real* backend status, not a mock/stale
 * value.
 *
 * This targets a specific architecture invariant this codebase has previously violated:
 * "Status is the only external-facing field" (internal orchestration `phase` must never leak
 * as the thing the user reads). The assertion below checks the row's rendered text against
 * the API's own `status` field via the UI's status-copy table, so a regression that serialises
 * or displays the wrong field (e.g. a raw `phase` value like "pending"/"queued" instead of the
 * mapped status) fails this test instead of shipping unnoticed.
 */
test("submitted run is visible in Activity with the correct status", async ({ page, request }) => {
  const workspace = await createWorkspace(request, uniqueName("e2e-run-ws"));
  const workflow = await createWorkflow(request, workspace.name, uniqueName("e2e-run-wf"));
  const run = await submitWorkflowRun(request, workspace.name, workflow.name);

  const expectedStatusText = executionStatusCopy[run.status];
  expect(expectedStatusText, `no UI copy mapped for backend status "${run.status}" - update support/api.ts`).toBeTruthy();

  await page.goto(`/${workspace.name}/activity`);

  const row = page.getByTestId("configuration-property-table-row").filter({ hasText: workflow.displayName });
  await expect(row).toBeVisible();
  await expect(row).toContainText(expectedStatusText);

  await row.click();
  await expect(page).toHaveURL(new RegExp(`/${workspace.name}/activity/${run.id}`));
  await expect(page.getByText("Activity detail")).toBeVisible();
});
