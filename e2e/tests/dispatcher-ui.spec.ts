import { test, expect, type Locator, type Page } from "@playwright/test";
import { APP_BASENAME, createWorkspace, executionStatusCopy, uniqueName, uiKebabName } from "../support/api";

/*
 * UI-driven dispatcher scenarios, deliberately in their OWN file (separate from the API-driven
 * tests/dispatcher-kube.spec.ts) so the engine-level suite and the UI journey can be run - or
 * skipped - independently of each other.
 *
 * The journey covered here is the one the API suite cannot: a person AUTHORS the workflow on the
 * canvas (drag a task from the palette, configure it, wire the edges, create a version) and runs
 * it from the UI, and the run executes through the real dispatcher on the real cluster. This is
 * exactly where a picker/form bug lives that no API test can catch.
 *
 * Requires the docker-compose.kube.yml stack (dispatcher against a laptop cluster) - gated by
 * E2E_DISPATCHER=true like the API suite.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";

/**
 * The palette items are HTML5-draggable (Task.tsx sets dataTransfer "application/reactflow"),
 * which Playwright's mouse-based dragTo does not drive; dispatch the real drag events with a
 * shared DataTransfer instead. The drop handler reads event.clientX/Y (Reactflow.tsx onDrop via
 * screenToFlowPosition), so the drop coordinates land the node where we want it on the canvas.
 */
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

/** Wire one edge by mouse-dragging from a node's source handle to another node's target handle. */
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

test.describe("dispatcher via the UI", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ timeout: 6 * 60_000 });

  test("author a workflow on the canvas and run it through the dispatcher", async ({ page, request }) => {
    const workspace = await createWorkspace(request, uniqueName("e2e-dispatcher-ui"));
    const workflowName = uniqueName("canvas-wf");

    // Create the workflow through the UI; success lands in the editor canvas.
    await page.goto(`${APP_BASENAME}/${workspace.name}/workflows`);
    await page.getByTestId("workflows-create-workflow-button").click();
    await page.locator("#displayName").fill(workflowName);
    await page.locator("#displayName").blur();
    const createButton = page.getByTestId("workflows-create-workflow-submit");
    await expect(createButton).toBeEnabled();
    await createButton.click();
    await expect(page).toHaveURL(new RegExp(`/${workspace.name}/editor/${uiKebabName(workflowName)}/canvas`));

    // Drag execute-shell (the palette lists and searches catalogue slugs) from the palette onto the canvas, between start and end.
    const canvas = page.locator(".react-flow").first();
    await expect(page.locator(".react-flow__node-start")).toBeVisible();
    await page.getByTestId("editor-task-search").fill("execute-shell");
    // The palette groups tasks in collapsed category accordions; expand to surface the match.
    await page.getByRole("button", { name: "Expand all" }).click();
    const paletteItem = page.getByRole("option", { name: "Execute Shell" }).first();
    await expect(paletteItem).toBeVisible();
    await dragPaletteItemToCanvas(page, paletteItem, canvas, { x: 420, y: 220 });
    const scriptNode = page.locator(".react-flow__node-script");
    await expect(scriptNode).toBeVisible();

    // Configure it: shell interpreter, and the script through the code-editor modal.
    await scriptNode.locator('[alt="Workflow edit button"]').click();
    const taskModal = page.getByRole("dialog").filter({ hasText: "Edit Execute Shell" });
    await expect(taskModal).toBeVisible();
    await taskModal.getByRole("textbox", { name: "Shell Interpreter" }).fill("sh");
    // The script field is a read-only textarea that opens the code-editor modal on click.
    await taskModal.locator("textarea[readonly]").last().click();
    const editorModal = page.getByRole("dialog").filter({ hasText: "Update Shell Script" });
    await expect(editorModal.locator(".CodeMirror")).toBeVisible();
    await editorModal.locator(".CodeMirror").click();
    await page.keyboard.type("echo ui-canvas-ok");
    await editorModal.getByRole("button", { name: "Update" }).click();
    await taskModal.getByRole("button", { name: "Apply" }).click();
    await expect(taskModal).toBeHidden();

    // Wire start -> task -> end.
    await connect(page, page.locator(".react-flow__node-start"), scriptNode);
    await connect(page, scriptNode, page.locator(".react-flow__node-end"));
    await expect(page.locator(".react-flow__edge")).toHaveCount(2);

    // Save as a new version.
    await page.getByRole("button", { name: "Create new version" }).click();
    await page.getByLabel("Version comment").fill("Authored by the dispatcher UI e2e test");
    await page.getByRole("button", { name: "Create", exact: true }).click();
    await expect(page.getByRole("dialog").filter({ hasText: "Create New Version" })).toBeHidden();

    // Run it from the workflows list; "Run and View" navigates to the run detail.
    await page.goto(`${APP_BASENAME}/${workspace.name}/workflows`);
    await page.getByRole("button", { name: "Run it" }).click();
    await page.getByRole("button", { name: "Run and View" }).click();
    await expect(page).toHaveURL(new RegExp(`/${workspace.name}/activity/`), { timeout: 30_000 });

    // The Activity list is the UI's own record of the outcome: poll it until the run shows the
    // real terminal status text (the dispatcher has to schedule a pod, so allow a few minutes).
    const succeeded = executionStatusCopy["succeeded"];
    await expect(async () => {
      await page.goto(`${APP_BASENAME}/${workspace.name}/activity`);
      const row = page.getByTestId("configuration-property-table-row").filter({ hasText: workflowName });
      await expect(row).toBeVisible();
      await expect(row).toContainText(succeeded, { timeout: 2_000 });
    }).toPass({ timeout: 4 * 60_000, intervals: [5_000] });
  });

  /*
   * Future: authoring the same workflow through a YAML editor in the UI. No such editor exists
   * today - the editor's tabs are Canvas / Parameters / Configure / Schedules / Change Log, the
   * only workflow import surface is the JSON file import on the workflows list, and YAML in/out
   * exists solely for task templates (Tekton format on /api/v2/task). When a workflow YAML (or
   * code) tab lands, replace this placeholder with: open the YAML view, paste the same
   * start -> execute-shell -> end definition, save a version, run it, and assert the Activity
   * status exactly as the canvas test does.
   */
  test.fixme("author a workflow via the YAML editor and run it through the dispatcher", async () => {
    // Intentionally unimplemented: no workflow YAML editor in the UI yet.
  });
});
