import { test as setup, expect } from "@playwright/test";
import { STORAGE_STATE } from "../playwright.config";
import { APP_BASENAME } from "../support/api";

/*
 * Login bootstrap for the secured stack (FLOW_SECURITY_ENABLED=true): signs in ONCE through the
 * real product flow - signed-out page -> server-side remix-auth code flow against the stack's IDPZero ->
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
  // The sign-in surface is SERVER-RENDERED (BFF slice: the root loader resolves /auth/config in
  // the same pass as the 401 and SignedOut renders its real mode server-side) - so the raw HTML,
  // before any JavaScript runs, already carries the Sign in button. Pin that with a plain
  // request: this is view-source, not the hydrated app.
  const unauthenticatedHtml = await (await page.request.get(`${APP_BASENAME}/`)).text();
  // React escapes the apostrophe in the SSR HTML ("You&#x27;re not signed in").
  expect(unauthenticatedHtml).toContain("re not signed in");
  // The oidc-mode surface specifically: the Sign in form POSTing to the sign-in action. The
  // plain signed-out text alone would also pass in mode "none" - the form is the mode proof.
  expect(unauthenticatedHtml).toContain(`action="${APP_BASENAME}/auth/signin"`);

  await page.goto(`${APP_BASENAME}/`);

  // Unauthenticated bootstrap -> the signed-out page, sign-in surface included.
  await expect(page.getByText("You're not signed in", { exact: true })).toBeVisible();
  const signIn = page.getByRole("button", { name: "Sign in" });
  await expect(signIn).toBeVisible();
  await signIn.click();

  // IDPZero's login page (issuer http://idp.localhost:4380 - see docker-compose.yml for why
  // that hostname): a passwordless user picker.
  await page.waitForURL(/idp\.localhost:4380\/login/);
  await page.locator('select[name="username"]').selectOption("usr-flow-admin");
  await page.getByRole("button", { name: "Sign In" }).click();

  // Back on the app: /auth/callback exchanges the code + id_token, sets the session cookie, and
  // hard-navigates to the return path. The navbar only renders on an authenticated bootstrap.
  const appOrigin = new URL(baseURL!).origin;
  await page.waitForURL(
    (url) => url.origin === appOrigin && !url.pathname.startsWith(`${APP_BASENAME}/auth/`),
  );
  await expect(page.getByRole("navigation", { name: "Platform navigation" })).toBeVisible({
    timeout: 15_000,
  });

  await page.context().storageState({ path: STORAGE_STATE });

  // Pre-run hygiene: this suite (and the fixture-creating specs) mint one throwaway workspace per
  // worker per run, named e2e-<kind>-<Date.now()>-<random>, and nothing else ever deletes them.
  // Delete ONLY those - a name must match the exact shape AND embed a timestamp older than a day -
  // so real workspaces (or anything not created by these tests) are never touched. Deletion
  // cascades the workspace's workflows, revisions and runs (WorkspaceService.delete).
  const dayMs = 24 * 60 * 60 * 1000;
  const apiOrigin = process.env.E2E_API_URL ?? "http://localhost:7700";
  const list = await page.request.get(`${apiOrigin}/api/v2/workspace/query?limit=100`);
  if (list.ok()) {
    const body = (await list.json()) as { content?: { name: string }[] };
    const stale = (body.content ?? [])
      .map((w) => ({ name: w.name, m: /^e2e-[a-z-]+-(\d{13})-\d+$/.exec(w.name) }))
      .filter((w) => w.m && Date.now() - Number(w.m[1]) > dayMs);
    for (const w of stale) {
      const res = await page.request.delete(`${apiOrigin}/api/v2/workspace/${w.name}`);
      console.log(`pre-run cleanup: ${w.name} -> ${res.status()}`);
    }
    console.log(`pre-run cleanup: removed ${stale.length} stale e2e workspace(s)`);
  }
});
