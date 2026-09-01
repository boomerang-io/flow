import { test, expect } from "@playwright/test";
import { execSync } from "node:child_process";
import { createWorkspace, uniqueName } from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  result,
  submitAndStart,
  task,
  taskLog,
  waitForRun,
  type WorkflowSpec,
} from "../support/dispatcher";

/*
 * End-to-end scenarios that need a REAL dispatcher executing container tasks on a REAL
 * Kubernetes cluster (docker-compose.kube.yml against a laptop cluster, or any deployment). The
 * default compose stack has no dispatcher, so this file is skipped unless E2E_DISPATCHER=true.
 *
 * Each scenario creates its own workflow in one shared workspace, submits it with start=true and
 * asserts the terminal run: statuses, typed statusReason values, results flowing between tasks,
 * decision routing, run-scoped storage, failure, timeout, child workflows, and pod-loss recovery.
 *
 * E2E_KUBECTL_CONTEXT (optional) enables the pod-kill scenario, which needs kubectl access to the
 * cluster the dispatcher targets.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";
const KUBE_CONTEXT = process.env.E2E_KUBECTL_CONTEXT;

test.describe("dispatcher on kubernetes", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ mode: "parallel", timeout: 6 * 60_000 });

  let workspace: string;

  test.beforeAll(async ({ request }) => {
    workspace = (await createWorkspace(request, uniqueName("e2e-dispatcher"))).name;
  });

  async function run(
    request: Parameters<typeof createWorkflowFromSpec>[0],
    spec: Omit<WorkflowSpec, "name"> & { name?: string },
    timeoutMs?: number,
  ) {
    const name = spec.name ?? uniqueName("wf");
    const wf = await createWorkflowFromSpec(request, workspace, { ...spec, name });
    const submitted = await submitAndStart(request, workspace, wf.name);
    const finished = await waitForRun(request, workspace, submitted.id, timeoutMs);
    return { wf, run: finished };
  }

  const start = { name: "start", type: "start" };
  // A shell task on the catalogue's execute-shell template. Results are written by the script to
  // $RESULTS_PATH (one JSON object for the Jobs executor), so these scenarios exercise Flow's
  // params-in/results-out contract without depending on any other CLI command in the image
  // (task-flow 3.1.0's `system jsonPathToProperty` crashes before writing its result).
  const shellTask = (
    name: string,
    script: string,
    extra: Partial<WorkflowSpec["tasks"][number]> = {},
    deps: { taskRef: string; decisionCondition?: string; executionCondition?: string }[] = [{ taskRef: "start" }],
  ) => ({
    name,
    type: "script",
    taskRef: "execute-shell",
    params: [
      { name: "shell", value: "sh" },
      { name: "script", value: script },
    ],
    dependencies: deps,
    ...extra,
  });
  const writeResult = (name: string, value: string) => `echo '{"${name}":"${value}"}' > "$RESULTS_PATH"`;
  // An omitted executionCondition means `always`, which deliberately lets a failed task's
  // successors run (finishWorkflow then finds a complete path and the run succeeds). Scenarios
  // that assert failure propagation therefore gate `end` on `success`.
  const endAfter = (...deps: string[]) => ({
    name: "end",
    type: "end",
    dependencies: deps.map((taskRef) => ({ taskRef })),
  });
  const endOnSuccessOf = (...deps: string[]) => ({
    name: "end",
    type: "end",
    dependencies: deps.map((taskRef) => ({ taskRef, executionCondition: "success" })),
  });

  test("a catalogue task runs in a pod and returns a declared result", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [
        start,
        shellTask("extract", `echo "PARAM_NAMES=$PARAM_NAMES"; ${writeResult("greeting", "hello from kube")}`, {
          results: [{ name: "greeting", description: "written to RESULTS_PATH by the script" }],
        }),
        endAfter("extract"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    const t = task(r, "extract");
    expect(t.status, describe(r)).toBe("succeeded");
    expect(result(t, "greeting"), describe(r)).toBe("hello from kube");
  });

  test("a script task runs a shell script", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [
        start,
        {
          name: "shell",
          type: "script",
          taskRef: "execute-shell",
          params: [
            { name: "shell", value: "sh" },
            { name: "script", value: "echo dispatcher-script-ok\nuname -a" },
          ],
          dependencies: [{ taskRef: "start" }],
        },
        endAfter("shell"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    const log = await taskLog(request, task(r, "shell").id);
    expect(log, "task log should stream the script output").toContain("dispatcher-script-ok");
  });

  test("a custom task runs an arbitrary image and command", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [
        start,
        {
          name: "alpine",
          type: "custom",
          taskRef: "run-custom-task",
          params: [
            { name: "image", value: "alpine:3.20" },
            { name: "command", value: "sh" },
            { name: "arguments", value: "-c\necho custom-ok > /dev/termination-log-check; echo custom-ok" },
          ],
          dependencies: [{ taskRef: "start" }],
        },
        endAfter("alpine"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    expect(task(r, "alpine").status, describe(r)).toBe("succeeded");
  });

  test("results flow between tasks and a decision routes one branch", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [
        start,
        shellTask("extract", writeResult("route", "left"), {
          results: [{ name: "route" }],
        }),
        {
          name: "route",
          type: "decision",
          taskRef: "switch",
          params: [{ name: "value", value: "$(tasks.extract.results.route)" }],
          dependencies: [{ taskRef: "extract" }],
        },
        shellTask("left", `echo "took the left branch"; ${writeResult("side", "left-ran")}`, { results: [{ name: "side" }] }, [
          { taskRef: "route", decisionCondition: "left" },
        ]),
        shellTask("right", writeResult("side", "right-ran"), { results: [{ name: "side" }] }, [
          { taskRef: "route", decisionCondition: "right" },
        ]),
        {
          name: "end",
          type: "end",
          dependencies: [
            { taskRef: "left", executionCondition: "always" },
            { taskRef: "right", executionCondition: "always" },
          ],
        },
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    expect(task(r, "left").status, describe(r)).toBe("succeeded");
    expect(result(task(r, "left"), "side"), describe(r)).toBe("left-ran");
    expect(task(r, "right").status, describe(r)).toBe("skipped");
  });

  test("run-scoped storage is shared between tasks", async ({ request }) => {
    const mount = "/workspace/run";
    const ws = [{ name: "run-store", type: "workflowrun", mountPath: mount }];
    const { run: r } = await run(request, {
      workspaces: [
        {
          name: "run-store",
          type: "workflowrun",
          optional: false,
          spec: { size: "1Gi", accessMode: "ReadWriteOnce", mountPath: mount },
        },
      ],
      tasks: [
        start,
        shellTask("write", `mkdir -p ${mount} && echo hello-storage > ${mount}/hello.txt && ls -la ${mount}`, { workspaces: ws }),
        shellTask(
          "check",
          `cat ${mount}/hello.txt && grep -q hello-storage ${mount}/hello.txt && ${writeResult("seen", "yes")}`,
          { workspaces: ws, results: [{ name: "seen" }] },
          [{ taskRef: "write" }],
        ),
        endAfter("check"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    expect(task(r, "check").status, describe(r)).toBe("succeeded");
    expect(result(task(r, "check"), "seen"), describe(r)).toBe("yes");
  });

  test("a failing task ends the run failed with a typed reason", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [
        start,
        {
          name: "boom",
          type: "script",
          taskRef: "execute-shell",
          params: [
            { name: "shell", value: "sh" },
            { name: "script", value: "echo about-to-fail\nexit 3" },
          ],
          dependencies: [{ taskRef: "start" }],
        },
        endOnSuccessOf("boom"),
      ],
    });
    expect(r.status, describe(r)).toBe("failed");
    const t = task(r, "boom");
    expect(t.status, describe(r)).toBe("failed");
    expect(t.statusReason, describe(r)).toBe("JobFailed");
  });

  test("a task that exceeds its timeout is reaped with DeadlineExceeded", async ({ request }) => {
    const { run: r } = await run(
      request,
      {
        tasks: [
          start,
          {
            name: "slow",
            type: "script",
            taskRef: "execute-shell",
            timeout: 1,
            params: [
              { name: "shell", value: "sh" },
              { name: "script", value: "sleep 300" },
            ],
            dependencies: [{ taskRef: "start" }],
          },
          endOnSuccessOf("slow"),
        ],
      },
      5 * 60_000,
    );
    const t = task(r, "slow");
    expect(["timedout", "failed"], describe(r)).toContain(t.status);
    expect(t.statusReason, describe(r)).toBe("DeadlineExceeded");
    expect(["timedout", "failed"], describe(r)).toContain(r.status);
  });

  test("a run-workflow task starts a child run", async ({ request }) => {
    const child = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("child"),
      tasks: [start, shellTask("child-work", "echo child-ran"), endAfter("child-work")],
    });
    const { run: r } = await run(request, {
      tasks: [
        start,
        {
          name: "spawn",
          type: "runworkflow",
          taskRef: "run-workflow",
          params: [{ name: "workflowRef", value: child.name }],
          dependencies: [{ taskRef: "start" }],
        },
        endAfter("spawn"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    const childRunId = result(task(r, "spawn"), "workflowRunRef");
    expect(childRunId, describe(r)).toBeTruthy();
    const childRun = await waitForRun(request, workspace, String(childRunId));
    expect(childRun.status, describe(childRun)).toBe("succeeded");
  });

  test("a pod deleted mid-run is detected by the reconcile loop", async ({ request }) => {
    test.skip(!KUBE_CONTEXT, "set E2E_KUBECTL_CONTEXT to the cluster the dispatcher targets");
    const name = uniqueName("podkill");
    const wf = await createWorkflowFromSpec(request, workspace, {
      name,
      tasks: [
        start,
        {
          name: "victim",
          type: "script",
          taskRef: "execute-shell",
          params: [
            { name: "shell", value: "sh" },
            { name: "script", value: "sleep 240" },
          ],
          dependencies: [{ taskRef: "start" }],
        },
        endOnSuccessOf("victim"),
      ],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);
    // Wait for the pod to exist, then delete it out from under the dispatcher.
    const selector = `boomerang.io/workflowrun-ref=${submitted.id}`;
    let pod = "";
    for (let i = 0; i < 40 && !pod; i++) {
      pod = execSync(
        `kubectl --context ${KUBE_CONTEXT} get pods -l ${selector} -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}'`,
        { encoding: "utf8" },
      ).trim();
      if (!pod) await new Promise((r) => setTimeout(r, 3000));
    }
    expect(pod, "a running pod for the task").toBeTruthy();
    execSync(`kubectl --context ${KUBE_CONTEXT} delete pod ${pod} --wait=false`, { encoding: "utf8" });
    const started = Date.now();
    const finished = await waitForRun(request, workspace, submitted.id, 3 * 60_000);
    const t = task(finished, "victim");
    expect(["failed", "timedout"], describe(finished)).toContain(t.status);
    expect(Date.now() - started, "detected well inside the task timeout").toBeLessThan(120_000);
  });
});
