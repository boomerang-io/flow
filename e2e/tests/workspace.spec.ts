import { test, expect } from "@playwright/test";
import { uniqueName, uiKebabName, getWorkspace, APP_BASENAME } from "../support/api";

/*
 * Create a workspace end to end through the UI, then confirm it actually exists in the real
 * backend (not just that a success toast appeared). Catches the class of defect where a
 * create action looks successful in the UI (optimistic update, stale cache) but the write
 * never reached - or was rejected by - the real API, which a mocked-backend test cannot see
 * (the retired Cypress suite ran entirely against miragejs mocks).
 */
test("create a workspace and see it in the workspace list", async ({ page, request }) => {
  const displayName = uniqueName("e2e-workspace");

  await page.goto(`${APP_BASENAME}/home`);

  await page.getByTestId("workflows-create-workflow-button").click();
  await page.getByTestId("text-input-workspace-name").fill(displayName);
  await page.getByTestId("save-workspace-name").click();

  // The modal closes on success; the new workspace card renders in "Your Workspaces". Scoped to
  // the card title because the name ALSO appears in the header's workspace switcher (a bare
  // getByText resolves to both and trips strict mode).
  await expect(page.getByTestId("workflow-card-title").filter({ hasText: displayName })).toBeVisible();

  // Cross-check against the real backend - the UI showing the card is not proof the write
  // persisted, only that the response looked like success. The UI kebab-cases the display name
  // into the resource name (see uiKebabName's contract note).
  const workspace = await getWorkspace(request, uiKebabName(displayName));
  expect(workspace.displayName).toBe(displayName);
});
