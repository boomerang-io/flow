import { defineConfig, devices } from "@playwright/test";

/*
 * Drives the real product (client-web UI + service-core API together), not the webapp in
 * isolation - see the compose stack at the repo root (docker-compose.yml) for the topology.
 * Points at the nginx gateway by default: client-web and service-core sit behind it on one
 * origin so the browser's direct API calls (PRODUCT_SERVICE_ENV_URL) are same-origin
 * (service-core has no CORS support - see docker/gateway/nginx.conf).
 */
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:8080";

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
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
