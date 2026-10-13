import { test, expect } from "@playwright/test";
import { APP_BASENAME } from "../support/api";

/*
 * The Manage area: "/admin" lands on a tab (the founding admin may see every tab, so the first by
 * order, Settings) and the tab row navigates. Runs as the admin the login bootstrap created
 * (tests/auth.setup.ts); a non-admin session, which should see no Manage link and a 403 here,
 * becomes testable once the permission enforcement flip lands (see admin-settings.spec.ts).
 */
test("manage: the root lands on the first tab and the tabs navigate", async ({ page }) => {
  await page.goto(`${APP_BASENAME}/admin`);

  // The redirect is server-side: the first response already carries the tab's URL.
  await expect(page).toHaveURL(/\/admin\/settings\/[^/?]+$/);
  await expect(page.getByRole("heading", { name: "Manage" })).toBeVisible();
  // The tab row, not the Settings side navigation, which also has an "Audit" group.
  const tabs = page.getByLabel("Manage pages");
  await expect(tabs.getByRole("link", { name: "Settings" })).toBeVisible();

  await tabs.getByRole("link", { name: "Audit" }).click();
  await expect(page).toHaveURL(/\/admin\/audit$/);
  await expect(page.getByRole("heading", { name: "Manage" })).toBeVisible();

  await tabs.getByRole("link", { name: "Users" }).click();
  await expect(page).toHaveURL(/\/admin\/users$/);
});
