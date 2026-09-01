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

  // "Configure Customizations" is not the first (auto-open) accordion group, so open it.
  await page.getByText("Configure Customizations").click();

  // By testid, not label: two settings render the label "App Name" (customizations' appName and
  // the GitHub integration's github.appName), so getByLabel trips strict mode.
  const appNameInput = page.getByTestId("appName");
  await appNameInput.fill(newAppName);

  // Every settings group renders its own Save (this branch's new Authentication Configuration
  // section sits ABOVE Customizations, and its Save stays disabled - a bare .first() clicks
  // that one and times out), so scope the click to the accordion item that holds this group.
  const customizations = page.locator("li.cds--accordion__item", { has: page.locator("#customizations") });
  await customizations.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Settings succesfully updated")).toBeVisible();

  // Reload to prove the value came back from the backend, not just local form state.
  await page.reload();
  await page.getByText("Configure Customizations").click();
  await expect(page.getByTestId("appName")).toHaveValue(newAppName);
});
