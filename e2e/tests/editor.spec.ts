import { expect } from "@playwright/test";
import { APP_BASENAME, createWorkflow, currentUser, uniqueName, workspaceTest } from "../support/api";

/*
 * The workflow editor's smaller surfaces: the task palette's search matches the names it shows
 * (display names, not only catalogue slugs), the Change Log names the person who saved each
 * version, and a path under the editor that it does not know lands on the canvas instead of an
 * empty page.
 */

const test = workspaceTest("e2e-editor");

test("the palette search finds a task by its display name", async ({ page, request, workspace }) => {
  const wf = await createWorkflow(request, workspace, uniqueName("e2e-palette"));
  await page.goto(`${APP_BASENAME}/${workspace}/editor/${wf.name}/canvas`);
  await expect(page.locator(".react-flow__node-start")).toBeVisible();

  await page.getByTestId("editor-task-search").fill("Execute Shell");
  await page.getByRole("button", { name: "Expand all" }).click();
  await expect(page.getByRole("option", { name: "Execute Shell" }).first()).toBeVisible();
  await expect(page.getByRole("option", { name: "Sleep", exact: true })).toHaveCount(0);
});

test("the Change Log shows who saved each version", async ({ page, request, workspace }) => {
  const me = await currentUser(request);
  const wf = await createWorkflow(request, workspace, uniqueName("e2e-changelog"));
  await page.goto(`${APP_BASENAME}/${workspace}/editor/${wf.name}/changelog`);

  const row = page.getByTestId("change-log-table-row");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(me.name);
});

test("an editor path it does not know redirects to the canvas", async ({ page, request, workspace }) => {
  const wf = await createWorkflow(request, workspace, uniqueName("e2e-editor-path"));
  await page.goto(`${APP_BASENAME}/${workspace}/editor/${wf.name}/no-such-tab`);

  await expect(page).toHaveURL(new RegExp(`${APP_BASENAME}/${workspace}/editor/${wf.name}/canvas$`));
  await expect(page.locator(".react-flow__node-start")).toBeVisible();
});
