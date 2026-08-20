import { test, expect } from "@playwright/test";
import { uniqueName } from "../support/api";

/*
 * Admin screen journey: change a platform setting seeded by service-loader
 * (service-loader/src/main/resources/seed/settings.json - "customizations"."appName") and
 * confirm it round-trips through the real backend and Mongo, not just local component state.
 *
 * This stack runs with flow.security.enabled=false (see docker-compose.yml), so it cannot
 * exercise real authorization - there is no login flow yet (specifications/authentication.md)
 * and every request is implicitly "whoever hits the endpoint". That is a real gap: this test
 * proves the settings screen and its API wiring work, it does NOT prove an unprivileged user
 * is denied. Once real authentication lands, extend this with a negative case (non-admin
 * session gets 403 / the UI hides the save action) - see the report's "what changes once auth
 * lands" note.
 */
test("admin settings: changing a platform setting persists", async ({ page }) => {
  const newAppName = uniqueName("e2e-app-name");

  await page.goto("/admin/settings");

  // "Configure Customizations" is not the first (auto-open) accordion group, so open it.
  await page.getByText("Configure Customizations").click();

  const appNameInput = page.getByLabel("App Name");
  await appNameInput.fill(newAppName);

  await page.getByRole("button", { name: "Save" }).first().click();
  await expect(page.getByText("Settings succesfully updated")).toBeVisible();

  // Reload to prove the value came back from the backend, not just local form state.
  await page.reload();
  await page.getByText("Configure Customizations").click();
  await expect(page.getByLabel("App Name")).toHaveValue(newAppName);
});
