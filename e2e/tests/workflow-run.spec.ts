import { test, expect } from "@playwright/test";
import {
  uniqueName,
  createWorkspace,
  createWorkflow,
  submitWorkflowRun,
  getWorkflowRun,
  executionStatusCopy,
  APP_BASENAME,
} from "../support/api";

/*
 * Submit a run via the API (no agent is part of this stack - see docker-compose.yml's
 * comment on why - so a started run stays `running`; it does not need to reach `succeeded` to
 * be a meaningful UI journey), then confirm the Activity list and
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

  await page.goto(`${APP_BASENAME}/${workspace.name}/activity`);

  const row = page.getByTestId("configuration-property-table-row").filter({ hasText: workflow.displayName });
  await expect(row).toBeVisible();
  // A submit starts its run, so the status moves on after the submit answers and after the page
  // loads: reload, then compare the row with the run's status as the API reports it now, retried
  // until both agree.
  await expect(async () => {
    await page.reload();
    await expect(row).toBeVisible();
    const current = await getWorkflowRun(request, workspace.name, run.id);
    const expectedStatusText = executionStatusCopy[current.status];
    expect(expectedStatusText, `no UI copy mapped for backend status "${current.status}" - update support/api.ts`).toBeTruthy();
    await expect(row).toContainText(expectedStatusText, { timeout: 2_000 });
  }).toPass({ timeout: 20_000 });

  await row.click();
  await expect(page).toHaveURL(new RegExp(`/${workspace.name}/activity/${run.id}`));
  await expect(page.getByText("Activity detail")).toBeVisible();
});
