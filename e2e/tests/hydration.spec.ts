import { expect, type Page } from "@playwright/test";
import { APP_BASENAME, createWorkflow, uniqueName, workspaceTest } from "../support/api";
import { submitAndStart, waitForRun } from "../support/dispatcher";

/*
 * Pages that render dates hydrate cleanly for a viewer outside UTC. The server renders in UTC; a
 * browser in Sydney (UTC+10/+11) that formatted the same date in its own timezone during
 * hydration would no longer match the server's HTML, and React throws: minified errors #418
 * (hydration mismatch), #422 (the root falls back to client rendering) and #425 (text content
 * mismatch). The run is the engine's own sleep task, finished, so it has a start time and a
 * duration to render; no dispatcher is needed.
 */

const test = workspaceTest("e2e-hydration");
test.use({ timezoneId: "Australia/Sydney" });

const HYDRATION_ERROR = /Minified React error #(418|422|425)|Hydration failed|did not match|hydrat/i;

/** Opens the page and returns the hydration errors it raised, uncaught or logged. */
async function hydrationErrors(page: Page, path: string): Promise<string[]> {
  const errors: string[] = [];
  const onPageError = (error: Error) => {
    if (HYDRATION_ERROR.test(String(error))) errors.push(`pageerror: ${String(error).slice(0, 200)}`);
  };
  const onConsole = (message: { type(): string; text(): string }) => {
    if (message.type() === "error" && HYDRATION_ERROR.test(message.text())) {
      errors.push(`console: ${message.text().slice(0, 200)}`);
    }
  };
  page.on("pageerror", onPageError);
  page.on("console", onConsole);
  await page.goto(`${APP_BASENAME}${path}`);
  await page.waitForLoadState("networkidle");
  page.off("pageerror", onPageError);
  page.off("console", onConsole);
  return errors;
}

test("Home, Activity and a run page hydrate without errors in Australia/Sydney", async ({
  page,
  request,
  workspace,
}) => {
  const wf = await createWorkflow(request, workspace, uniqueName("e2e-tz"));
  const run = await waitForRun(
    request,
    workspace,
    (await submitAndStart(request, workspace, wf.name)).id,
    60_000,
    1_000,
  );
  expect(run.status).toBe("succeeded");
  expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)).toBe("Australia/Sydney");

  for (const path of ["/home", `/${workspace}/activity`, `/${workspace}/activity/${run.id}`]) {
    expect.soft(await hydrationErrors(page, path), `hydration errors on ${path}`).toEqual([]);
  }
});
