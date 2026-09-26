import { test, expect, type Locator, type Page } from "@playwright/test";
import { APP_BASENAME, createWorkspace, uniqueName } from "../support/api";
import { createWorkflowFromSpec, describe, result, submitAndStart, task, waitForRun } from "../support/dispatcher";

/*
 * The For each journey in the editor (decision 0083): the palette's "For each" entry, the
 * "Repeat a task for each item" dialog, the task form's Parameters | Configure tabs, the canvas
 * badge surviving a save and reload (the backend's canvas conversion carries foreach both ways),
 * and the run view grouping items under their task. Gated like the other dispatcher suites.
 * Screenshots land in test-results/foreach-ui/ for review.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";
const API_ORIGIN = process.env.E2E_API_URL ?? "http://localhost:7700";
const SHOTS = "test-results/foreach-ui";

/** See dispatcher-ui.spec.ts: HTML5 drag events dispatched with a shared DataTransfer. */
async function dragPaletteItemToCanvas(page: Page, source: Locator, target: Locator, at: { x: number; y: number }) {
  const src = await source.elementHandle();
  const tgt = await target.elementHandle();
  const box = await target.boundingBox();
  if (!src || !tgt || !box) throw new Error("drag source/target not resolvable");
  await page.evaluate(
    ([s, t, x, y]) => {
      const dataTransfer = new DataTransfer();
      const opts = { bubbles: true, cancelable: true, composed: true, dataTransfer } as DragEventInit;
      (s as Element).dispatchEvent(new DragEvent("dragstart", opts));
      (t as Element).dispatchEvent(new DragEvent("dragenter", opts));
      (t as Element).dispatchEvent(new DragEvent("dragover", { ...opts, clientX: x as number, clientY: y as number }));
      (t as Element).dispatchEvent(new DragEvent("drop", { ...opts, clientX: x as number, clientY: y as number }));
      (s as Element).dispatchEvent(new DragEvent("dragend", opts));
    },
    [src, tgt, box.x + at.x, box.y + at.y] as const,
  );
}

async function connect(page: Page, fromNode: Locator, toNode: Locator) {
  const from = fromNode.locator(".react-flow__handle.source");
  const to = toNode.locator(".react-flow__handle.target");
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  if (!a || !b) throw new Error("handles not visible");
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 });
  await page.mouse.up();
}

test.describe("for each in the editor", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ timeout: 6 * 60_000 });

  test("add a for-each task from the palette, save, reload and run it", async ({ page, request }) => {
    const workspace = (await createWorkspace(request, uniqueName("e2e-foreach-ui"))).name;
    // start -> stage -> end exists already; the UI adds fan between stage and end.
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("fe-ui"),
      tasks: [
        { name: "start", type: "start" },
        {
          name: "stage",
          type: "script",
          taskRef: "execute-shell",
          params: [
            { name: "shell", value: "sh" },
            { name: "script", value: `echo '{"batches":["a","b","c"]}' > "$RESULTS_PATH"` },
          ],
          results: [{ name: "batches" }],
          dependencies: [{ taskRef: "start" }],
        },
        { name: "end", type: "end", dependencies: [{ taskRef: "stage" }] },
      ],
    });

    await page.goto(`${APP_BASENAME}/${workspace}/editor/${wf.name}/canvas`);
    const canvas = page.locator(".react-flow").first();
    await expect(page.locator(".react-flow__node-start")).toBeVisible();

    // Palette: For each is offered under the (open by default) Workflow category.
    const forEach = page.getByRole("option", { name: "For each" });
    await expect(forEach).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/1-palette.png` });

    await dragPaletteItemToCanvas(page, forEach, canvas, { x: 520, y: 380 });
    const dialog = page.getByRole("dialog").filter({ hasText: "Repeat a task for each item" });
    await expect(dialog).toBeVisible();

    await dialog.getByRole("combobox", { name: "Task" }).click();
    await dialog.getByRole("combobox", { name: "Task" }).fill("Execute Shell");
    await page.getByRole("option", { name: "Execute Shell" }).first().click();
    await dialog.getByRole("textbox", { name: "Items" }).fill("$(tasks.stage.results.batches)");
    // A bracket in the name shows the reserved-name error and holds Next.
    const name = dialog.getByRole("textbox", { name: "Task Name" });
    await name.fill("fan[0]");
    await name.blur();
    await expect(dialog.getByText("Task names cannot contain [ or ]")).toBeVisible();
    const next = dialog.getByRole("button", { name: "Next: task parameters" });
    await expect(next).toBeDisabled();
    await page.screenshot({ path: `${SHOTS}/2-dialog-invalid-name.png` });
    await name.fill("fan");
    await name.blur();
    await expect(dialog.getByText("Task names cannot contain [ or ]")).toBeHidden();
    await expect(next).toBeEnabled();
    await page.screenshot({ path: `${SHOTS}/2-dialog.png` });
    await next.click();

    // The task's own form opens straight away: Task Name above Parameters | Configure.
    const taskModal = page.getByRole("dialog").filter({ hasText: "Edit Execute Shell" });
    await expect(taskModal).toBeVisible();
    await expect(taskModal.getByRole("textbox", { name: "Task Name" })).toHaveValue("fan");
    const tabs = taskModal.getByRole("tab");
    await expect(tabs).toHaveText([/Parameters/, /Configure/]);
    const nameBox = await taskModal.getByRole("textbox", { name: "Task Name" }).boundingBox();
    const tabBox = await tabs.first().boundingBox();
    expect(nameBox!.y, "Task Name sits above the tabs").toBeLessThan(tabBox!.y);
    await page.screenshot({ path: `${SHOTS}/3-parameters-tab.png` });

    await taskModal.getByRole("textbox", { name: "Shell Interpreter" }).fill("sh");
    await taskModal.locator("textarea[readonly]").last().click();
    const editorModal = page.getByRole("dialog").filter({ hasText: "Update Shell Script" });
    await expect(editorModal.locator(".CodeMirror")).toBeVisible();
    await editorModal.locator(".CodeMirror").click();
    await page.keyboard.type("echo item-$(params.item)-at-$(params.index)");
    await editorModal.getByRole("button", { name: "Update" }).click();

    const configure = taskModal.getByRole("tab", { name: /Configure/ });
    await expect(configure.getByText("For each")).toBeVisible();
    await configure.click();
    await expect(taskModal.getByRole("switch", { name: "Run for each item" })).toBeChecked();
    await expect(taskModal.getByRole("textbox", { name: "Items" })).toHaveValue("$(tasks.stage.results.batches)");
    await page.screenshot({ path: `${SHOTS}/3-configure-tab.png` });

    // The same bracket rule holds in the task form.
    await taskModal.getByRole("textbox", { name: "Task Name" }).fill("fan]");
    await taskModal.getByRole("textbox", { name: "Task Name" }).blur();
    await expect(taskModal.getByText("Task names cannot contain [ or ]")).toBeVisible();
    await taskModal.getByRole("textbox", { name: "Task Name" }).fill("fan");
    await taskModal.getByRole("textbox", { name: "Task Name" }).blur();

    await taskModal.getByRole("button", { name: "Apply" }).click();
    await expect(taskModal).toBeHidden();

    const fanNode = page.locator(".react-flow__node-script").filter({ has: page.getByTestId("foreach-badge") });
    await expect(fanNode).toHaveCount(1);
    await expect(fanNode.getByTestId("foreach-badge")).toHaveText("For each");
    const stageNode = page.locator(".react-flow__node-script").filter({ hasNot: page.getByTestId("foreach-badge") });
    await connect(page, stageNode, fanNode);
    await connect(page, fanNode, page.locator(".react-flow__node-end"));
    await expect(page.locator(".react-flow__edge")).toHaveCount(4);

    await page.getByRole("button", { name: "Create new version" }).click();
    await page.getByLabel("Version comment").fill("For each via the palette");
    await page.getByRole("button", { name: "Create", exact: true }).click();
    await expect(page.getByRole("dialog").filter({ hasText: "Create New Version" })).toBeHidden();

    // The stored workflow carries foreach on the task, and a reload draws the badge again.
    const saved = await (await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${wf.name}`)).json();
    const savedFan = (saved.tasks ?? []).find((t: { name: string }) => t.name === "fan");
    expect(savedFan?.foreach, JSON.stringify(saved.tasks)).toEqual({ items: "$(tasks.stage.results.batches)" });
    await page.reload();
    await expect(page.locator(".react-flow__node-script").getByTestId("foreach-badge")).toHaveText("For each");
    await page.screenshot({ path: `${SHOTS}/4-canvas-node.png` });

    // Run it and view the run: the items group under their task with a summary.
    const submitted = await submitAndStart(request, workspace, wf.name);
    const finished = await waitForRun(request, workspace, submitted.id, 4 * 60_000);
    expect(finished.status, describe(finished)).toBe("succeeded");
    expect(task(finished, "fan").statusMessage).toBe("All 3 items succeeded.");
    expect(result(task(finished, "stage"), "batches")).toBeTruthy();

    await page.goto(`${APP_BASENAME}/${workspace}/activity/${submitted.id}`);
    await expect(page.getByTestId("foreach-summary")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("foreach-summary")).toContainText("3");
    await expect(page.locator(".react-flow__node-script").getByTestId("foreach-badge")).toHaveText(
      "For each · 3 of 3 succeeded",
    );
    await page.screenshot({ path: `${SHOTS}/5-run-view.png`, fullPage: true });

    // The parent never ran a pod, so only its items offer a log.
    await expect(page.locator("#task-fan").getByRole("button", { name: "View Log" })).toHaveCount(0);
    // The task's items expand under it, one row per item.
    await page.getByRole("button", { name: /Show items/ }).click();
    const itemRows = page.getByTestId("foreach-item");
    await expect(itemRows).toHaveCount(3);
    await expect(itemRows.nth(0)).toContainText("[0]");
    await expect(itemRows.nth(1)).toContainText("b");
    await expect(itemRows.nth(2)).toContainText("Succeeded");
    await expect(itemRows.getByRole("button", { name: "View Log" })).toHaveCount(3);
    await page.screenshot({ path: `${SHOTS}/6-run-view-items.png`, fullPage: true });
  });
});
