import { expect } from "@playwright/test";
import { API_ORIGIN, APP_BASENAME, uniqueName, workspaceTest } from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  endAfter,
  sleepTask,
  startTask,
  waitForRun,
  type WorkflowRunView,
} from "../support/dispatcher";

/*
 * A run-once schedule fires its workflow. The schedule is created through the API a few seconds
 * ahead: the Schedules form picks a time to the minute, which would make the test wait up to a
 * minute more for nothing it does not already prove. The schedule watcher claims due schedules
 * every 30 s, so the run appears within about 40 s. The run records the `schedule` trigger and the
 * schedule's params, Activity shows it as Schedule, and its page links to the schedule; the
 * schedule is then deleted. The workflow is the engine's own sleep task, so no dispatcher is
 * needed.
 */

const test = workspaceTest("e2e-schedule");

type Schedule = { id: string; name: string; status: string; type: string };

test("a run-once schedule fires with the schedule trigger, shows in Activity, and is deleted", async ({
  page,
  request,
  workspace,
}) => {
  test.setTimeout(3 * 60_000);
  const wf = await createWorkflowFromSpec(request, workspace, {
    name: uniqueName("scheduled"),
    params: [{ name: "who", type: "text", default: "nobody" }],
    // A schedule fires only while the workflow's Scheduler switch is on.
    triggers: { schedule: { enabled: true } },
    tasks: [startTask, sleepTask("nap", 1), endAfter("nap")],
  });

  const create = await request.post(`${API_ORIGIN}/api/v2/workspace/${workspace}/schedule`, {
    data: {
      name: uniqueName("once"),
      type: "runOnce",
      workflowRef: wf.name,
      timezone: "UTC",
      dateSchedule: new Date(Date.now() + 10_000).toISOString(),
      params: [{ name: "who", value: "the-schedule" }],
    },
  });
  expect(create.status(), await create.text()).toBe(200);
  const schedule = (await create.json()) as Schedule;
  expect(schedule.status).toBe("active");

  // Listed on the Schedules page while it waits to fire.
  await page.goto(`${APP_BASENAME}/${workspace}/schedules`);
  await expect(page.getByText(schedule.name).first()).toBeVisible();

  let fired: WorkflowRunView | undefined;
  await expect
    .poll(
      async () => {
        const res = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/query?limit=10`);
        fired = ((await res.json()) as { content: WorkflowRunView[] }).content[0];
        return fired?.trigger;
      },
      { timeout: 90_000, intervals: [3_000], message: "the schedule fires within one watcher interval" },
    )
    .toBe("schedule");
  const run = await waitForRun(request, workspace, String(fired?.id), 60_000, 2_000);
  expect(run.status, describe(run)).toBe("succeeded");
  expect(run.initiatedByRef, describe(run)).toBe(schedule.id);
  expect(run.params?.find((p) => p.name === "who")?.value, describe(run)).toBe("the-schedule");

  await page.goto(`${APP_BASENAME}/${workspace}/activity`);
  const row = page.getByTestId("configuration-property-table-row").filter({ hasText: wf.name });
  await expect(row).toBeVisible();
  // Soft, so a wrong label still lets the rest of the journey (the link, the delete) report.
  await expect.soft(row.getByText("Schedule", { exact: true })).toBeVisible();
  await row.click();
  await expect(page.getByTestId("initiated-by-schedule-link")).toHaveText(schedule.id);

  const removed = await request.delete(`${API_ORIGIN}/api/v2/workspace/${workspace}/schedule/${schedule.id}`);
  expect(removed.status(), await removed.text()).toBeLessThan(300);
  const remaining = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/schedule/query?limit=50`);
  expect(((await remaining.json()) as { content: Schedule[] }).content.map((s) => s.id)).not.toContain(schedule.id);
});
