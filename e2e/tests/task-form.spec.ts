import { test, expect } from "@playwright/test";
import { APP_BASENAME, createWorkspace, uniqueName } from "../support/api";
import { createWorkflowFromSpec } from "../support/dispatcher";

const API_ORIGIN = process.env.E2E_API_URL ?? "http://localhost:7700";

type SavedTask = { name: string; params?: { name: string; value: unknown }[]; results?: { name: string }[] };

/*
 * Editing a catalogue task in the default task form and saving a version. The form shows the
 * template's declared results read-only; they must never come back as a param, which the backend
 * rejects on save as an undeclared param.
 */
test("edit a template task's param, apply and save a version", async ({ page, request }) => {
  const workspace = (await createWorkspace(request, uniqueName("e2e-task-form"))).name;
  const wf = await createWorkflowFromSpec(request, workspace, {
    name: uniqueName("task-form"),
    tasks: [
      { name: "start", type: "start" },
      {
        name: "read",
        type: "template",
        taskRef: "read-file-to-parameter",
        params: [{ name: "path", value: "/workspace/before.txt" }],
        dependencies: [{ taskRef: "start" }],
      },
      { name: "end", type: "end", dependencies: [{ taskRef: "read" }] },
    ],
  });
  const before = await (await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${wf.name}`)).json();

  await page.goto(`${APP_BASENAME}/${workspace}/editor/${wf.name}/canvas`);
  const node = page.locator(".react-flow__node-template");
  await expect(node).toBeVisible();
  await node.getByRole("button", { name: "edit" }).click();

  const taskModal = page.getByRole("dialog").filter({ hasText: "Edit Read File to Parameter" });
  await expect(taskModal).toBeVisible();
  // The template's declared result is shown read-only.
  await expect(taskModal.getByRole("button", { name: /^content:/ })).toBeVisible();
  await taskModal.getByRole("textbox", { name: "File Path" }).fill("/workspace/after.txt");
  await taskModal.getByRole("button", { name: "Apply" }).click();
  await expect(taskModal).toBeHidden();

  // Any failed request while saving, so a rejected save reports the backend's reason.
  const failures: string[] = [];
  page.on("response", async (response) => {
    if (response.status() >= 400) failures.push(`${response.status()} ${response.url()} ${await response.text()}`);
  });
  await page.getByRole("button", { name: "Create new version" }).click();
  await page.getByLabel("Version comment").fill("Edit a template task");
  await page.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page.getByRole("dialog").filter({ hasText: "Create New Version" })).toBeHidden();

  // The save landed: a new version whose task carries the edited param and no `results` param.
  await expect
    .poll(async () => {
      const saved = await (await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${wf.name}`)).json();
      return saved.version;
    })
    .toBeGreaterThan(before.version)
    .catch((error) => {
      throw new Error(`${error.message}\nfailed requests:\n${failures.join("\n")}`);
    });
  const saved = await (await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow/${wf.name}`)).json();
  expect(failures, failures.join("\n")).toEqual([]);
  const read = (saved.tasks as SavedTask[]).find((t) => t.name === "read");
  expect(read?.params, JSON.stringify(read)).toEqual([{ name: "path", value: "/workspace/after.txt" }]);
});
