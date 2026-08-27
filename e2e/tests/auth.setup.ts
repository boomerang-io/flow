import { test as setup, expect } from "@playwright/test";
import { STORAGE_STATE } from "../playwright.config";

/*
 * Login bootstrap for the secured stack (FLOW_SECURITY_ENABLED=true): signs in ONCE through the
 * real product flow - signed-out page -> browser-side PKCE against the stack's IDPZero ->
 * POST /api/v2/auth/exchange mints the httpOnly flow_session cookie - and saves the resulting
 * storage state for every other project to reuse (playwright.config.ts wires this file as the
 * `setup` project the `chromium` project depends on). The specs' `page` AND `request` fixtures
 * both pick the cookie up from that storage state, so the support/api.ts helpers stay
 * authenticated too.
 *
 * Signs in as usr-flow-admin (docker/idpzero/server.yaml): the first sign-in on a fresh database
 * founds the admin, which admin-settings.spec.ts relies on.
 */
setup("sign in via IDPZero and save storage state", async ({ page, baseURL }) => {
  await page.goto("/");

  // Unauthenticated bootstrap -> the signed-out page. The sign-in surface only appears after
  // hydration resolves GET /api/v2/auth/config to mode=oidc (SignedOut.tsx), hence the waits.
  await expect(page.getByText("You're not signed in")).toBeVisible();
  const signIn = page.getByRole("button", { name: "Sign in" });
  await expect(signIn).toBeVisible();
  await signIn.click();

  // IDPZero's login page (issuer http://idp.localhost:4379 - see docker-compose.yml for why
  // that hostname): a passwordless user picker.
  await page.waitForURL(/idp\.localhost:4379\/login/);
  await page.locator('select[name="username"]').selectOption("usr-flow-admin");
  await page.getByRole("button", { name: "Sign In" }).click();

  // Back on the app: /auth/callback exchanges the code + id_token, sets the session cookie, and
  // hard-navigates to the return path. The navbar only renders on an authenticated bootstrap.
  const appOrigin = new URL(baseURL!).origin;
  await page.waitForURL((url) => url.origin === appOrigin && !url.pathname.startsWith("/auth/"));
  await expect(page.getByRole("navigation", { name: "nav" })).toBeVisible({ timeout: 15_000 });

  await page.context().storageState({ path: STORAGE_STATE });
});
