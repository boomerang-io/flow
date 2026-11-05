import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { API_ORIGIN, APP_BASENAME, uniqueName, workspaceTest } from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  endOnSuccessOf,
  startTask,
  submitAndStart,
  task,
  waitForRun,
  waitForRunState,
  type WorkflowTaskSpec,
} from "../support/dispatcher";

/*
 * Approvals and manual tasks decided on the Actions page. The engine runs both task types itself,
 * so these runs need no dispatcher. Each run parks on its action; the person decides it in the
 * Actions table's dialog, and the run carries on: an approval or a task completed successfully
 * lets it succeed, a rejection fails it (`end` waits on the action's success). The header's
 * "Today's numbers" count the workspace's waiting actions and the share of today's decided
 * approvals that were approved, so each test owns its workspace.
 */

const test = workspaceTest("e2e-actions");

const approvalTask: WorkflowTaskSpec = {
  name: "approve",
  type: "approval",
  taskRef: "manual-approval",
  params: [{ name: "numberOfApprovals", value: "1" }],
  dependencies: [{ taskRef: "start" }],
};
const manualTask: WorkflowTaskSpec = {
  name: "check",
  type: "manual",
  taskRef: "manual-task",
  params: [{ name: "instructions", value: "Confirm the **e2e** checklist is done." }],
  dependencies: [{ taskRef: "start" }],
};

type ActionView = { id: string; type: string; status: string; workflowRunRef: string };

/** Submits a workflow whose one task is `waitOn`, and waits until its action is open. */
async function runWaitingOn(request: APIRequestContext, workspace: string, waitOn: WorkflowTaskSpec) {
  const wf = await createWorkflowFromSpec(request, workspace, {
    name: uniqueName(waitOn.type),
    tasks: [startTask, waitOn, endOnSuccessOf(waitOn.name)],
  });
  const run = await submitAndStart(request, workspace, wf.name);
  await waitForRunState(request, workspace, run.id, (r) => task(r, waitOn.name).status === "waiting", 30_000, 1_000);
  return { wf, run };
}

async function actionFor(request: APIRequestContext, workspace: string, runId: string): Promise<ActionView> {
  const res = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/action/query?limit=50`);
  expect(res.ok(), await res.text()).toBe(true);
  const found = ((await res.json()) as { content: ActionView[] }).content.find((a) => a.workflowRunRef === runId);
  if (!found) throw new Error(`no action for run ${runId}`);
  return found;
}

/** Opens the action's row on its Actions tab; the row opens the decision dialog. */
async function openAction(page: Page, workspace: string, tab: "approvals" | "manual", workflowName: string) {
  await page.goto(`${APP_BASENAME}/${workspace}/actions/${tab}`);
  await page.waitForLoadState("networkidle");
  await page.getByTestId("configuration-property-table-row").filter({ hasText: workflowName }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  return dialog;
}

test("an approval approved on the Actions page lets the run succeed", async ({ page, request, workspace }) => {
  const { wf, run } = await runWaitingOn(request, workspace, approvalTask);

  const dialog = await openAction(page, workspace, "approvals", wf.name);
  await dialog.getByLabel("Comments (optional)").fill("Approved by the e2e suite");
  await dialog.getByRole("button", { name: "Approve", exact: true }).click();
  await expect(dialog).toBeHidden();

  const finished = await waitForRun(request, workspace, run.id, 60_000, 1_000);
  expect(finished.status, describe(finished)).toBe("succeeded");
  expect((await actionFor(request, workspace, run.id)).status).toBe("approved");
});

test("an approval rejected on the Actions page fails the run", async ({ page, request, workspace }) => {
  const { wf, run } = await runWaitingOn(request, workspace, approvalTask);

  const dialog = await openAction(page, workspace, "approvals", wf.name);
  // A Carbon danger button carries a visually hidden "danger" in its accessible name.
  await dialog.getByRole("button", { name: /^(danger )?Reject$/ }).click();
  await expect(dialog).toBeHidden();

  const finished = await waitForRun(request, workspace, run.id, 60_000, 1_000);
  expect(finished.status, describe(finished)).toBe("failed");
  expect(task(finished, "approve").status, describe(finished)).toBe("failed");
  expect((await actionFor(request, workspace, run.id)).status).toBe("rejected");
});

test("a manual task completed on the Actions page lets the run succeed", async ({ page, request, workspace }) => {
  const { wf, run } = await runWaitingOn(request, workspace, manualTask);

  const dialog = await openAction(page, workspace, "manual", wf.name);
  // The task's instructions render as Markdown.
  await expect(dialog.locator("strong", { hasText: "e2e" })).toBeVisible();
  await dialog.getByRole("button", { name: "Complete Successfully" }).click();
  await expect(dialog).toBeHidden();

  const finished = await waitForRun(request, workspace, run.id, 60_000, 1_000);
  expect(finished.status, describe(finished)).toBe("succeeded");
  expect((await actionFor(request, workspace, run.id)).status).toBe("approved");
});

test("Today's numbers count the waiting actions and today's approval rate", async ({ page, request, workspace }) => {
  // Two approvals decided (one approved, one rejected), one approval and one manual task waiting.
  const decided = [
    await runWaitingOn(request, workspace, approvalTask),
    await runWaitingOn(request, workspace, approvalTask),
  ];
  await runWaitingOn(request, workspace, approvalTask);
  await runWaitingOn(request, workspace, manualTask);
  for (const [index, { run }] of decided.entries()) {
    const action = await actionFor(request, workspace, run.id);
    const res = await request.put(`${API_ORIGIN}/api/v2/workspace/${workspace}/action`, {
      data: [{ id: action.id, approved: index === 0, comments: "" }],
    });
    expect(res.ok(), await res.text()).toBe(true);
  }

  await page.goto(`${APP_BASENAME}/${workspace}/actions/approvals`);
  const numbers = page.locator("section").filter({ has: page.getByText("Today's numbers", { exact: true }) });
  // Each widget is its figure, then its caption.
  const figure = (caption: string) =>
    numbers
      .locator("div")
      .filter({ has: page.getByText(caption, { exact: true }) })
      .last()
      .locator("p")
      .first();
  await expect(figure("Manual")).toHaveText("1");
  await expect(figure("Approval")).toHaveText("1");
  await expect(figure("Approval rate")).toHaveText("50%");
});
