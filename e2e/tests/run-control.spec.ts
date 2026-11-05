import { expect, type Page } from "@playwright/test";
import { execSync } from "node:child_process";
import { API_ORIGIN, APP_BASENAME, patchWorkspace, uniqueName, workspaceTest } from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  endOnSuccessOf,
  getRun,
  shellTask,
  sleepTask,
  startTask,
  submitAndStart,
  task,
  waitForRun,
  waitForRunState,
  writeResult,
  type WorkflowRunView,
} from "../support/dispatcher";

/*
 * The run page's controls, driven the way a person drives them: pause and resume, retry a failed
 * run from where it failed, and cancel a run whose task is still running. Each test owns its
 * workspace (support/api.ts workspaceTest), so the workspace parameter the retry flips and the
 * workspace's concurrent-run quota are never shared.
 *
 * Pause and resume run on the engine's own sleep task and need no dispatcher. Retry and cancel run
 * shell tasks in pods, so they are gated by E2E_DISPATCHER=true like the other dispatcher suites;
 * E2E_KUBECTL_CONTEXT adds the check that a cancelled run's pod is removed.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";
const KUBE_CONTEXT = process.env.E2E_KUBECTL_CONTEXT;

const test = workspaceTest("e2e-runctl");

async function openRun(page: Page, workspace: string, runId: string) {
  await page.goto(`${APP_BASENAME}/${workspace}/activity/${runId}`);
  // The controls act once the page has hydrated; the run page then polls every 5 s.
  await page.waitForLoadState("networkidle");
}

/** Accepts the run page's confirmation dialog (ConfirmModal's affirmative button is "Yes"). */
async function confirm(page: Page, title: string) {
  const dialog = page.getByRole("dialog").filter({ hasText: title });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Yes" }).click();
}

test.describe("pause and resume", () => {
  test.describe.configure({ timeout: 2 * 60_000 });

  test("a run paused from the run page starts no new task until it is resumed", async ({
    page,
    request,
    workspace,
  }) => {
    // The first sleep keeps the run busy long enough to pause it; the second is the task a pause holds back.
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("pause"),
      tasks: [startTask, sleepTask("first", 15_000), sleepTask("second", 1, "first"), endOnSuccessOf("second")],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);
    await waitForRunState(request, workspace, submitted.id, (r) => task(r, "first").status === "waiting");

    await openRun(page, workspace, submitted.id);
    await page.getByTestId("pause-run").click();
    await confirm(page, "Pause run");
    await expect(page.getByTestId("paused-indicator")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("resume-run")).toBeVisible();
    expect((await getRun(request, workspace, submitted.id)).paused).toBe(true);

    // The task in flight finishes; the one after it is held while the run stays paused.
    const afterFirst = await waitForRunState(
      request,
      workspace,
      submitted.id,
      (r) => task(r, "first").status === "succeeded",
      45_000,
    );
    expect(afterFirst.status, describe(afterFirst)).toBe("running");
    await page.waitForTimeout(6_000);
    const held = await getRun(request, workspace, submitted.id);
    expect(task(held, "second").status, describe(held)).toBe("notstarted");

    await page.getByTestId("resume-run").click();
    await confirm(page, "Resume run");
    const finished = await waitForRun(request, workspace, submitted.id, 60_000);
    expect(finished.status, describe(finished)).toBe("succeeded");
    expect(finished.paused, describe(finished)).toBeFalsy();
    await expect(page.getByTestId("run-status")).toContainText("Succeeded", { timeout: 15_000 });
    await expect(page.getByTestId("paused-indicator")).toBeHidden();
  });
});

test.describe("retry and cancel on kubernetes", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ timeout: 6 * 60_000 });

  // A task that succeeds, then one that fails unless its mode reads "pass".
  const flakyTasks = (mode: string) => [
    startTask,
    shellTask("prepare", writeResult("prepared", "yes"), { results: [{ name: "prepared" }] }),
    shellTask("flaky", `mode="${mode}"; echo "mode=$mode"; [ "$mode" = pass ] || exit 3`, {}, [{ taskRef: "prepare" }]),
    endOnSuccessOf("flaky"),
  ];

  /** The retried run reuses the task that succeeded and re-runs only the one that failed. */
  function expectRetriedFromFailure(origin: WorkflowRunView, retried: WorkflowRunView) {
    expect(retried.status, describe(retried)).toBe("succeeded");
    expect(retried.trigger, describe(retried)).toBe("retry");
    expect(retried.initiatedByRef, describe(retried)).toBe(origin.id);
    expect(task(retried, "prepare").reusedFromRef, describe(retried)).toBe(task(origin, "prepare").id);
    expect(task(retried, "flaky").reusedFromRef, describe(retried)).toBeFalsy();
    expect(task(retried, "flaky").status, describe(retried)).toBe("succeeded");
  }

  test("a failed run retried from the run page re-runs only its failed task and succeeds", async ({
    page,
    request,
    workspace,
  }) => {
    // The run page's retry carries the origin run's params, so the value that makes the task pass
    // comes from a workspace parameter, which every task run reads afresh when it resolves.
    await patchWorkspace(request, workspace, { parameters: [{ name: "retryMode", type: "text", value: "fail" }] });
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("retry-ui"),
      tasks: flakyTasks("$(workspace.params.retryMode)"),
    });
    const origin = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(origin.status, describe(origin)).toBe("failed");
    expect(task(origin, "flaky").status, describe(origin)).toBe("failed");
    await patchWorkspace(request, workspace, { parameters: [{ name: "retryMode", type: "text", value: "pass" }] });

    await openRun(page, workspace, origin.id);
    await page.getByTestId("retry-run").click();
    const modal = page.getByRole("dialog").filter({ hasText: "Retry run" });
    await expect(modal).toBeVisible();
    await modal.getByText("The tasks that did not succeed, and everything after them").click();
    await modal.getByTestId("retry-run-submit").click();

    // The page moves to the run the retry created, which starts by itself.
    await expect(page).toHaveURL(new RegExp(`/${workspace}/activity/(?!${origin.id})[0-9a-f]{24}$`), {
      timeout: 30_000,
    });
    await expect.soft(page.getByText("Retry started")).toBeVisible();
    const retriedId = page.url().split("/").pop() ?? "";
    const started = await waitForRunState(request, workspace, retriedId, (r) => r.status !== "ready", 30_000);
    expect(started.phase, `the retry's run should start without a Start click: ${describe(started)}`).not.toBe(
      "pending",
    );
    const retried = await waitForRun(request, workspace, retriedId);
    expectRetriedFromFailure(origin, retried);

    // The header links back to the original run, and the carried-over task is marked as reused.
    const originLink = page.getByTestId("initiated-by-origin-run-link");
    await expect(originLink).toHaveText("Original run");
    await expect(originLink).toHaveAttribute("href", `${APP_BASENAME}/${workspace}/activity/${origin.id}`);
    await expect(page.getByTestId("retry-from")).toContainText("from the tasks that did not succeed");
    await expect(page.getByTestId("run-status")).toContainText("Succeeded", { timeout: 15_000 });
    await expect(page.locator("li#task-prepare").getByText("Reused", { exact: true })).toBeVisible();
    await expect(page.locator("li#task-flaky").getByText("Reused", { exact: true })).toHaveCount(0);
  });

  test("a failed run retried through the API with a param override re-runs its failed task", async ({
    request,
    workspace,
  }) => {
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("retry-api"),
      params: [{ name: "mode", type: "text", default: "fail" }],
      tasks: flakyTasks("$(params.mode)"),
    });
    const origin = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(origin.status, describe(origin)).toBe("failed");

    // A retry creates the new run; starting it is a separate call, as the Start button makes it.
    const retry = await request.put(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${origin.id}/retry`, {
      data: { scope: "failed", params: [{ name: "mode", value: "pass" }] },
    });
    expect(retry.status(), await retry.text()).toBe(200);
    const created = (await retry.json()) as WorkflowRunView;
    const start = await request.put(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${created.id}/start`, {
      data: {},
    });
    expect(start.status(), await start.text()).toBe(200);

    const retried = await waitForRun(request, workspace, created.id);
    expectRetriedFromFailure(origin, retried);
    expect(retried.params?.find((p) => p.name === "mode")?.value, describe(retried)).toBe("pass");
  });

  test("a running run cancelled from the run page ends cancelled and its pod is removed", async ({
    page,
    request,
    workspace,
  }) => {
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("cancel"),
      tasks: [startTask, shellTask("long", "echo started; sleep 300"), endOnSuccessOf("long")],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);
    await waitForRunState(
      request,
      workspace,
      submitted.id,
      (r) => task(r, "long").status === "running",
      3 * 60_000,
      3_000,
    );

    const selector = `boomerang.io/workflowrun-ref=${submitted.id}`;
    const pods = () =>
      execSync(`kubectl --context ${KUBE_CONTEXT} get pods -l ${selector} -o jsonpath='{.items[*].metadata.name}'`, {
        encoding: "utf8",
      }).trim();
    if (KUBE_CONTEXT) {
      expect(pods(), "the running task has a pod").not.toBe("");
    }

    await openRun(page, workspace, submitted.id);
    await page.getByRole("button", { name: "More run actions" }).click();
    await page.getByRole("menuitem", { name: "Cancel run" }).click();
    await confirm(page, "Cancel run");

    const finished = await waitForRun(request, workspace, submitted.id, 60_000);
    expect(finished.status, describe(finished)).toBe("cancelled");
    expect(task(finished, "long").status, describe(finished)).toBe("cancelled");
    await expect(page.getByTestId("run-status")).toContainText("Cancelled", { timeout: 15_000 });

    if (KUBE_CONTEXT) {
      // The dispatcher stops the task on the terminate order; its Job and pod go with it.
      await expect.poll(pods, { timeout: 90_000, intervals: [3_000] }).toBe("");
    }
  });
});
