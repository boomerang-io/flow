import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

/*
 * Drives the real product (client-web UI + service-core API together), not the webapp in
 * isolation - see the compose stack at the repo root (docker-compose.yml) for the topology.
 * Points at client-web's own SSR server by default - the single browser-facing origin, and the
 * ONLY thing the browser talks to (BFF end state: documents, /res/* resource routes, .data
 * requests - never /api/*; the old /api forward is deleted). Test setup helpers call
 * service-core's API on its own origin instead (support/api.ts's API_ORIGIN). There is no
 * separate gateway.
 */
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/*
 * The stack is secured (FLOW_SECURITY_ENABLED=true in docker-compose.yml), so every spec runs
 * with a real session: the `setup` project signs in once through the actual IDPZero PKCE flow
 * (tests/auth.setup.ts) and saves the resulting flow_session cookie here; the `chromium`
 * project reuses it via storageState - for both the `page` fixture and the `request` fixture
 * that the support/api.ts helpers run on. Git-ignored (e2e/.gitignore): it holds a live
 * session token.
 */
export const STORAGE_STATE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  ".auth",
  "user.json",
);

export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: STORAGE_STATE },
      dependencies: ["setup"],
    },
  ],
});
