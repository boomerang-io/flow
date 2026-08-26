import { test, expect } from "@playwright/test";
import { uniqueName, getWorkspace } from "../support/api";

/*
 * Create a workspace end to end through the UI, then confirm it actually exists in the real
 * backend (not just that a success toast appeared). Catches the class of defect where a
 * create action looks successful in the UI (optimistic update, stale cache) but the write
 * never reached - or was rejected by - the real API, which a mocked-backend test cannot see
 * (the retired Cypress suite ran entirely against miragejs mocks).
 */
test("create a workspace and see it in the workspace list", async ({ page, request }) => {
  const displayName = uniqueName("e2e-workspace");

  await page.goto("/home");

  await page.getByTestId("workflows-create-workflow-button").click();
  await page.getByTestId("text-input-workspace-name").fill(displayName);
  await page.getByTestId("save-workspace-name").click();

  // The modal closes on success; the new workspace card renders in "Your Workspaces".
  await expect(page.getByText(displayName)).toBeVisible();

  // Cross-check against the real backend - the UI showing the card is not proof the write
  // persisted, only that the response looked like success.
  const kebabName = displayName.toLowerCase();
  const workspace = await getWorkspace(request, kebabName);
  expect(workspace.displayName).toBe(displayName);
});
