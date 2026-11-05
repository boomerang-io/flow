import { expect } from "@playwright/test";
import { createHash, randomBytes } from "node:crypto";
import {
  API_ORIGIN,
  APP_BASENAME,
  getWorkspace,
  patchWorkspace,
  uniqueName,
  workspaceTest,
  type RestErrorResponse,
  type WorkspaceView,
} from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  endOnSuccessOf,
  result,
  shellTask,
  startTask,
  submitAndStart,
  task,
  waitForRun,
} from "../support/dispatcher";

/*
 * Parameter values reaching a task, and the checks at save. A workspace parameter and a workflow
 * parameter are both read by a task through their references; a secured (password) workspace
 * parameter reads back masked in the API and the Parameters page while the task still receives
 * the real value. The task proves that by returning the value's length and SHA-256, never the
 * value. The runs are shell tasks in pods, gated by E2E_DISPATCHER=true; the save check is not.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";

const test = workspaceTest("e2e-params");

test("a workflow param whose type names no field type is refused at save", async ({ request, workspace }) => {
  // "string" is a run-param type, not one of the editor's field types (text, password, select...).
  const res = await request.post(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow`, {
    data: {
      name: uniqueName("bad-param"),
      tasks: [
        { name: "start", type: "start" },
        { name: "end", type: "end", dependencies: [{ taskRef: "start" }] },
      ],
      params: [{ name: "region", type: "string", default: "ap-southeast-2" }],
    },
  });
  expect(res.status(), await res.text()).toBe(400);
  const body = (await res.json()) as RestErrorResponse;
  expect(body.reason).toBe("PARAM_INVALID_TYPE");
  expect(body.message).toContain('"string"');
});

test.describe("parameters reaching a task on kubernetes", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ timeout: 6 * 60_000 });

  test("a workspace param and a workflow param reach a task", async ({ request, workspace }) => {
    await patchWorkspace(request, workspace, {
      parameters: [{ name: "region", type: "text", value: "ap-southeast-2" }],
    });
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("params"),
      params: [{ name: "greeting", type: "text", default: "gday" }],
      tasks: [
        startTask,
        shellTask(
          "read",
          `echo '{"region":"$(workspace.params.region)","greeting":"$(params.greeting)"}' > "$RESULTS_PATH"`,
          { results: [{ name: "region" }, { name: "greeting" }] },
        ),
        endOnSuccessOf("read"),
      ],
    });
    const r = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(r.status, describe(r)).toBe("succeeded");
    expect(result(task(r, "read"), "region"), describe(r)).toBe("ap-southeast-2");
    expect(result(task(r, "read"), "greeting"), describe(r)).toBe("gday");
  });

  test("a secured workspace param is hidden in the API and the UI, and its task gets the real value", async ({
    page,
    request,
    workspace,
  }) => {
    const secret = `e2e-${randomBytes(12).toString("hex")}`;
    const digest = createHash("sha256").update(secret).digest("hex");
    await patchWorkspace(request, workspace, {
      parameters: [{ name: "apiKey", type: "password", value: secret, description: "secured by the e2e suite" }],
    });

    // The API reads it back masked.
    const stored = ((await getWorkspace(request, workspace)) as WorkspaceView).parameters.find(
      (p) => p.name === "apiKey",
    );
    expect(stored?.hiddenValue).toBe(true);
    expect(stored?.value).not.toBe(secret);

    // The Parameters page marks it Secured and never renders the value.
    await page.goto(`${APP_BASENAME}/${workspace}/parameters`);
    const row = page.getByRole("row").filter({ hasText: "apiKey" });
    await expect(row).toBeVisible();
    await expect(row).toContainText("Secured");
    await expect(page.locator("body")).not.toContainText(secret);

    // The task receives the value: it reports its length and digest. Backticks, not $(...), so the
    // engine reads only the parameter reference as one.
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("secret"),
      tasks: [
        startTask,
        shellTask(
          "check",
          [
            `s='$(workspace.params.apiKey)'`,
            "sha=`printf %s \"$s\" | sha256sum | cut -d' ' -f1`",
            `echo "{\\"length\\":\\"\${#s}\\",\\"sha\\":\\"$sha\\"}" > "$RESULTS_PATH"`,
          ].join("\n"),
          { results: [{ name: "length" }, { name: "sha" }] },
        ),
        endOnSuccessOf("check"),
      ],
    });
    const r = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(r.status, describe(r)).toBe("succeeded");
    expect(result(task(r, "check"), "length"), describe(r)).toBe(String(secret.length));
    expect(result(task(r, "check"), "sha"), "the task saw the stored value").toBe(digest);
  });
});
