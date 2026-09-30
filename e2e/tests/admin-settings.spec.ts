import { test, expect } from "@playwright/test";
import { uniqueName, APP_BASENAME } from "../support/api";

/*
 * Admin screen journey: change a platform setting seeded by service-loader
 * (service-loader/src/main/resources/seed/settings.json - "customizations"."appName") and
 * confirm it round-trips through the real backend and Mongo, not just local component state.
 *
 * The stack is secured (FLOW_SECURITY_ENABLED=true) and this runs as the founding admin (the
 * login bootstrap's first sign-in - tests/auth.setup.ts). It proves the settings screen and its
 * API wiring work for an ADMIN; it does NOT yet prove an unprivileged user is denied - the
 * SecurityInterceptor still soft-fails permission checks (see CLAUDE.md's enforcement-flip
 * hazard), so a meaningful negative case (non-admin session gets 403 / the UI hides the save
 * action) only becomes testable once that flip lands. Extend this spec then.
 */
test("admin settings: changing a platform setting persists", async ({ page }) => {
  const newAppName = uniqueName("e2e-app-name");

  await page.goto(`${APP_BASENAME}/admin/settings`);

  // Settings is one group at a time, chosen in the side navigation; the page opens on the first
  // group by name, so pick Customizations.
  await page.getByRole("link", { name: "Customization" }).click();

  // By testid, not label: two settings render the label "App Name" (customizations' appName and
  // the GitHub integration's github.appName), so getByLabel trips strict mode.
  const appNameInput = page.getByTestId("appName");
  await appNameInput.fill(newAppName);

  // One save bar per page: the selected group's.
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Settings succesfully updated")).toBeVisible();

  // Reload to prove the value came back from the backend, not just local form state.
  await page.reload();
  await page.getByRole("link", { name: "Customization" }).click();
  await expect(page.getByTestId("appName")).toHaveValue(newAppName);
});
