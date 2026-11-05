import { expect } from "@playwright/test";
import { API_ORIGIN, uniqueName, workspaceTest } from "../support/api";
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
 * A workflow-scoped artifact carries a file from one run of a workflow to its next. Each run
 * downloads the artifact (optional, so the first run finds none), records what it read, writes
 * its own run id into the file and uploads it again. The first run's download reports found=false
 * and reads nothing; the second's reports found=true and reads the first run's id. The tasks run
 * in pods and share the run's own storage, gated by E2E_DISPATCHER=true.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";

const test = workspaceTest("e2e-artifacts");

test.describe("artifacts on kubernetes", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ timeout: 6 * 60_000 });

  test("a workflow-scoped artifact written by one run is read by the next", async ({ request, workspace }) => {
    const mount = "/workspace/run";
    const file = `${mount}/handoff.txt`;
    const artifact = { name: "handoff.txt", path: file, scope: "workflow" };
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("artifacts"),
      workspace: { size: "1Gi", accessMode: "ReadWriteOnce", mountPath: mount },
      tasks: [
        startTask,
        {
          name: "fetch",
          type: "downloadartifact",
          taskRef: "download-artifact",
          params: [
            ...Object.entries(artifact).map(([name, value]) => ({ name, value })),
            { name: "optional", value: "true" },
          ],
          dependencies: [{ taskRef: "start" }],
        },
        shellTask(
          "handoff",
          [
            `previous=none; [ -f ${file} ] && previous=\`cat ${file}\``,
            `echo "{\\"previous\\":\\"$previous\\"}" > "$RESULTS_PATH"`,
            `echo "$(context.params.workflowrun-ref)" > ${file}`,
          ].join("\n"),
          { results: [{ name: "previous" }] },
          [{ taskRef: "fetch" }],
        ),
        {
          name: "store",
          type: "uploadartifact",
          taskRef: "upload-artifact",
          params: Object.entries(artifact).map(([name, value]) => ({ name, value })),
          dependencies: [{ taskRef: "handoff" }],
        },
        endOnSuccessOf("store"),
      ],
    });

    const first = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(first.status, describe(first)).toBe("succeeded");
    expect(String(result(task(first, "fetch"), "found")), describe(first)).toBe("false");
    expect(result(task(first, "handoff"), "previous"), describe(first)).toBe("none");

    const stored = await request.get(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${first.id}/artifacts`);
    const artifacts = (await stored.json()) as { name: string; scope: string; status: string }[];
    expect(artifacts).toContainEqual(
      expect.objectContaining({ name: "handoff.txt", scope: "workflow", status: "available" }),
    );

    const second = await waitForRun(request, workspace, (await submitAndStart(request, workspace, wf.name)).id);
    expect(second.status, describe(second)).toBe("succeeded");
    // Soft, so the file's content is still checked when the flag is wrong.
    expect.soft(String(result(task(second, "fetch"), "found")), describe(second)).toBe("true");
    expect(result(task(second, "handoff"), "previous"), describe(second)).toBe(first.id);
  });
});
