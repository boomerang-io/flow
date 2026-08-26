import { test, expect } from "@playwright/test";
import { uniqueName, createWorkspace } from "../support/api";

/*
 * Create a workflow through the UI inside a fresh workspace, then confirm it shows up again
 * when searching the workflow list. This exercises the workspace-scoped workflow list/search,
 * which is exactly where a real defect lived: the source's search input testid is
 * `workflows-workspace-search` (renamed from a `workflows-team-search`-era name, part of the
 * Team-to-Workspace rename), but the retired Cypress spec still asserted the old
 * `workflows-team-search` testid - a rename the mocked test suite never caught because it
 * never ran against the renamed component in a way that failed loudly. This test uses the
 * current selector, so it fails the moment the two drift again.
 */
test("create a workflow and find it via workspace search", async ({ page, request }) => {
  const workspace = await createWorkspace(request, uniqueName("e2e-workflow-ws"));
  const workflowName = uniqueName("e2e-workflow");

  await page.goto(`/${workspace.name}/workflows`);

  await page.getByTestId("workflows-create-workflow-button").click();
  await page.locator("#displayName").fill(workflowName);
  // #name auto-derives from #displayName (kebab-case) - leave it, just confirm it populated.
  await expect(page.locator("#name")).toHaveValue(workflowName.toLowerCase());
  await page.getByTestId("workflows-create-workflow-submit").click();

  // Successful creation navigates straight into the editor canvas for the new workflow.
  await expect(page).toHaveURL(new RegExp(`/${workspace.name}/editor/${workflowName.toLowerCase()}/canvas`));

  // Back to the list: the workflow must be findable by its real, current search affordance.
  await page.goto(`/${workspace.name}/workflows`);
  await page.getByTestId("workflows-workspace-search").fill(workflowName);
  await expect(page.getByTestId("workflow-card-title").filter({ hasText: workflowName })).toBeVisible();

  await page.getByTestId("workflows-workspace-search").fill("definitely-does-not-exist-workflow");
  await expect(page.getByTestId("workflow-card-title")).toHaveCount(0);
});
