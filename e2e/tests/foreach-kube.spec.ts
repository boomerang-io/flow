import { test, expect, type APIRequestContext } from "@playwright/test";
import { execSync } from "node:child_process";
import { createWorkspace, uniqueName } from "../support/api";
import {
  createWorkflowFromSpec,
  describe,
  getRun,
  result,
  submitAndStart,
  task,
  taskLog,
  waitForRun,
  type TaskRunView,
  type WorkflowRunView,
  type WorkflowSpec,
} from "../support/dispatcher";

/*
 * For-each tasks (a `foreach` setting on a workflow task, decision 0082) end to end: the engine
 * fans one task out into one ordinary claimable task run per item, a REAL dispatcher runs each
 * item as its own Kubernetes Job, and the parent collects each declared result into an array in
 * item order. Gated exactly like dispatcher-kube.spec.ts: E2E_DISPATCHER=true against the
 * docker-compose.kube.yml stack, and E2E_KUBECTL_CONTEXT for the checks that read the cluster.
 */

const ENABLED = process.env.E2E_DISPATCHER === "true";
const KUBE_CONTEXT = process.env.E2E_KUBECTL_CONTEXT;
const API_ORIGIN = process.env.E2E_API_URL ?? "http://localhost:7700";


test.describe("for-each tasks on kubernetes", () => {
  test.skip(!ENABLED, "set E2E_DISPATCHER=true against a stack that runs service-dispatcher");
  test.describe.configure({ mode: "parallel", timeout: 6 * 60_000 });

  let workspace: string;

  test.beforeAll(async ({ request }) => {
    workspace = (await createWorkspace(request, uniqueName("e2e-foreach"))).name;
  });

  const start = { name: "start", type: "start" };
  const mount = "/workspace/run";
  const runStore = [
    {
      name: "run-store",
      type: "workflowrun",
      optional: false,
      spec: { size: "1Gi", accessMode: "ReadWriteOnce", mountPath: mount },
    },
  ];
  const taskStore = [{ name: "run-store", type: "workflowrun", mountPath: mount }];

  // A shell task on the catalogue's execute-shell template; results go to $RESULTS_PATH as one
  // JSON object (see dispatcher-kube.spec.ts).
  const shellTask = (
    name: string,
    script: string,
    extra: Record<string, unknown> = {},
    deps: { taskRef: string; executionCondition?: string }[] = [{ taskRef: "start" }],
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

  // stage writes the batches result and a file on the run's workspace that every item reads.
  const stage = shellTask(
    "stage",
    [
      `mkdir -p ${mount}`,
      `echo staged-by-stage > ${mount}/staged.txt`,
      `echo '{"batches":["a","b","c"]}' > "$RESULTS_PATH"`,
    ].join("\n"),
    { results: [{ name: "batches" }], workspaces: taskStore },
  );

  // Each item sleeps long enough that parallel items overlap, proves it sees the workspace file,
  // and writes out = done-<item>. failOn makes one item exit 1.
  const fan = (failOn?: string) =>
    shellTask(
      "fan",
      [
        `echo "item=$(params.item) index=$(params.index)"`,
        `grep -q staged-by-stage ${mount}/staged.txt`,
        `sleep 8`,
        failOn ? `if [ "$(params.item)" = "${failOn}" ]; then echo failing-on-purpose; exit 1; fi` : "",
        `echo '{"out":"done-$(params.item)"}' > "$RESULTS_PATH"`,
      ]
        .filter(Boolean)
        .join("\n"),
      {
        foreach: { items: "$(tasks.stage.results.batches)" },
        results: [{ name: "out" }],
        workspaces: taskStore,
      },
      [{ taskRef: "stage" }],
    );

  // report receives the parent's collected array; a quoted heredoc keeps its JSON intact.
  const report = (executionCondition?: string) =>
    shellTask(
      "report",
      "cat <<'EOF'\nreport-got=$(tasks.fan.results.out)\nEOF",
      {},
      [{ taskRef: "fan", ...(executionCondition ? { executionCondition } : {}) }],
    );

  const items = (run: WorkflowRunView, parentName: string): TaskRunView[] =>
    ((run.tasks ?? []) as TaskRunView[])
      .filter((t) => t.name.startsWith(`${parentName}[`))
      .sort((a, b) => (a.index ?? 0) - (b.index ?? 0));

  function jobsForRun(runId: string): { name: string; taskRunRef: string; start?: string; end?: string }[] {
    const out = execSync(
      `kubectl --context ${KUBE_CONTEXT} get jobs -A -l boomerang.io/workflowrun-ref=${runId} -o json`,
      { encoding: "utf8" },
    );
    return (JSON.parse(out).items ?? []).map((j: any) => ({
      name: j.metadata.name,
      taskRunRef: j.metadata.labels?.["boomerang.io/taskrun-ref"],
      start: j.status?.startTime,
      end: j.status?.completionTime,
    }));
  }

  // Two items overlapped in time when each one started before the other ended.
  function overlapping(a: TaskRunView, b: TaskRunView): boolean {
    const aStart = Date.parse(a.startTime!);
    const bStart = Date.parse(b.startTime!);
    return aStart < bStart + (b.duration ?? 0) && bStart < aStart + (a.duration ?? 0);
  }

  async function run(request: APIRequestContext, spec: Omit<WorkflowSpec, "name">) {
    const wf = await createWorkflowFromSpec(request, workspace, { ...spec, name: uniqueName("fe") });
    const submitted = await submitAndStart(request, workspace, wf.name);
    return waitForRun(request, workspace, submitted.id, 5 * 60_000);
  }

  test("stage -> for each -> report runs one item per element and collects the results in order", async ({
    request,
  }) => {
    const r = await run(request, {
      workspaces: runStore,
      tasks: [start, stage, fan(), report(), { name: "end", type: "end", dependencies: [{ taskRef: "report" }] }],
    });
    expect(r.status, describe(r)).toBe("succeeded");

    const parent = task(r, "fan");
    expect(parent.status, describe(r)).toBe("succeeded");
    expect(parent.parentRef, "the parent is not an item").toBeFalsy();
    expect(parent.statusMessage).toBe("All 3 items succeeded.");
    expect(result(parent, "out"), describe(r)).toEqual(["done-a", "done-b", "done-c"]);

    const fanItems = items(r, "fan");
    expect(fanItems.map((i) => i.name)).toEqual(["fan[0]", "fan[1]", "fan[2]"]);
    fanItems.forEach((item, index) => {
      expect(item.parentRef).toBe(parent.id);
      expect(item.index).toBe(index);
      expect(item.status, describe(r)).toBe("succeeded");
      expect(result(item, "out")).toBe(`done-${["a", "b", "c"][index]}`);
      const params = Object.fromEntries((item.params ?? []).map((p) => [p.name, p.value]));
      expect(params.item).toBe(["a", "b", "c"][index]);
      expect(String(params.index)).toBe(String(index));
    });
    // Items overlap in time: they ran in parallel, not one after another.
    expect(overlapping(fanItems[0], fanItems[1]) || overlapping(fanItems[1], fanItems[2]), JSON.stringify(fanItems.map((i) => [i.startTime, i.duration]))).toBe(true);

    const reportRun = task(r, "report");
    expect(reportRun.status, describe(r)).toBe("succeeded");
    const script = String((reportRun.params ?? []).find((p) => p.name === "script")?.value ?? "");
    for (const value of ["done-a", "done-b", "done-c"]) {
      expect(script, "report's resolved script").toContain(value);
    }
    const log = await taskLog(request, reportRun.id);
    expect(log).toContain("report-got=");
    expect(log).toContain("done-c");

    if (KUBE_CONTEXT) {
      const jobs = jobsForRun(r.id);
      const itemIds = new Set(fanItems.map((i) => i.id));
      const itemJobs = jobs.filter((j) => itemIds.has(j.taskRunRef));
      expect(itemJobs.length, JSON.stringify(jobs)).toBe(3);
      expect(jobs.some((j) => j.taskRunRef === parent.id), "the parent never runs as a Job").toBe(false);
    }
  });

  test("one failed item fails the task, and an always connection still runs the next task", async ({ request }) => {
    const r = await run(request, {
      workspaces: runStore,
      tasks: [
        start,
        stage,
        fan("b"),
        report("always"),
        { name: "end", type: "end", dependencies: [{ taskRef: "report" }] },
      ],
    });
    const parent = task(r, "fan");
    expect(parent.status, describe(r)).toBe("failed");
    expect(parent.statusReason, describe(r)).toBe("ItemFailed");
    expect(parent.statusMessage, describe(r)).toBe("1 of 3 items did not succeed.");
    expect(result(parent, "out"), describe(r)).toEqual(["done-a", null, "done-c"]);

    const fanItems = items(r, "fan");
    expect(fanItems.map((i) => i.status), describe(r)).toEqual(["succeeded", "failed", "succeeded"]);
    expect(fanItems[1].statusReason, describe(r)).toBe("JobFailed");

    expect(task(r, "report").status, describe(r)).toBe("succeeded");
    const script = String(((task(r, "report")).params ?? []).find((p) => p.name === "script")?.value ?? "");
    expect(script).toContain("null");
  });

  test("save rejects a bracketed task name and invalid for-each items", async ({ request }) => {
    const create = (tasks: unknown[]) =>
      request.post(`${API_ORIGIN}/api/v2/workspace/${workspace}/workflow`, {
        data: { name: uniqueName("fe-bad"), displayName: "bad", tasks },
      });
    const end = (dep: string) => ({ name: "end", type: "end", dependencies: [{ taskRef: dep }] });

    const bracketed = await create([start, shellTask("fan[0]", "echo hi"), end("fan[0]")]);
    expect(bracketed.status()).toBe(400);
    const bracketedBody = await bracketed.json();
    expect(bracketedBody.code, JSON.stringify(bracketedBody)).toBe(1214);

    const overCap = await create([
      start,
      shellTask("fan", "echo $(params.item)", { foreach: { items: Array.from({ length: 257 }, (_, i) => i) } }),
      end("fan"),
    ]);
    expect(overCap.status()).toBe(400);
    const overCapBody = await overCap.json();
    expect(overCapBody.code, JSON.stringify(overCapBody)).toBe(1213);
    expect(overCapBody.message).toContain("257");

    const notArray = await create([
      start,
      shellTask("fan", "echo $(params.item)", { foreach: { items: { a: 1 } } }),
      end("fan"),
    ]);
    expect(notArray.status()).toBe(400);
    const notArrayBody = await notArray.json();
    expect(notArrayBody.code, JSON.stringify(notArrayBody)).toBe(1213);

    // A literal array at the cap is accepted.
    const atCap = await create([
      start,
      shellTask("fan", "echo $(params.item)", { foreach: { items: Array.from({ length: 256 }, (_, i) => i) } }),
      end("fan"),
    ]);
    expect(atCap.status(), await atCap.text()).toBe(200);
  });

  test("a run paused during the fan-out completes after resume", async ({ request }) => {
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("fe-pause"),
      workspaces: runStore,
      tasks: [start, stage, fan(), report(), { name: "end", type: "end", dependencies: [{ taskRef: "report" }] }],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);
    const runUrl = `${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${submitted.id}`;

    // Pause once the items exist and are running.
    let r = await getRun(request, workspace, submitted.id);
    const deadline = Date.now() + 3 * 60_000;
    while (!items(r, "fan").some((i) => i.status === "running") && Date.now() < deadline) {
      await new Promise((res) => setTimeout(res, 1000));
      r = await getRun(request, workspace, submitted.id);
    }
    expect(items(r, "fan").length, describe(r)).toBe(3);
    const paused = await request.put(`${runUrl}/pause`);
    expect(paused.ok(), await paused.text()).toBe(true);

    // The running items finish; the parent completes; report is held by the pause.
    const heldDeadline = Date.now() + 3 * 60_000;
    do {
      await new Promise((res) => setTimeout(res, 3000));
      r = await getRun(request, workspace, submitted.id);
    } while (task(r, "fan").status !== "succeeded" && Date.now() < heldDeadline);
    expect(task(r, "fan").status, describe(r)).toBe("succeeded");
    await new Promise((res) => setTimeout(res, 5000));
    r = await getRun(request, workspace, submitted.id);
    expect(["notstarted", "ready"], describe(r)).toContain(task(r, "report").status);
    expect(r.status, describe(r)).toBe("running");

    const resumed = await request.put(`${runUrl}/resume`);
    expect(resumed.ok(), await resumed.text()).toBe(true);
    const finished = await waitForRun(request, workspace, submitted.id, 3 * 60_000);
    expect(finished.status, describe(finished)).toBe("succeeded");
    expect(result(task(finished, "fan"), "out")).toEqual(["done-a", "done-b", "done-c"]);
  });

  test("a run paused before the fan-out creates and runs every item after resume", async ({ request }) => {
    // stage sleeps so the pause lands while it runs: the fan-out is then admitted only on resume.
    const slowStage = shellTask(
      "stage",
      [`sleep 10`, `mkdir -p ${mount}`, `echo staged-by-stage > ${mount}/staged.txt`, `echo '{"batches":["a","b","c"]}' > "$RESULTS_PATH"`].join("\n"),
      { results: [{ name: "batches" }], workspaces: taskStore },
    );
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("fe-prepause"),
      workspaces: runStore,
      tasks: [start, slowStage, fan(), report(), { name: "end", type: "end", dependencies: [{ taskRef: "report" }] }],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);
    const runUrl = `${API_ORIGIN}/api/v2/workspace/${workspace}/workflowrun/${submitted.id}`;
    // Pause answers 200 but does nothing until the run is running (the run starts asynchronously
    // after submit), so wait for stage to be running first.
    let r = await getRun(request, workspace, submitted.id);
    const runningDeadline = Date.now() + 2 * 60_000;
    while (task(r, "stage").status !== "running" && Date.now() < runningDeadline) {
      await new Promise((res) => setTimeout(res, 500));
      r = await getRun(request, workspace, submitted.id);
    }
    expect(task(r, "stage").status, describe(r)).toBe("running");
    const paused = await request.put(`${runUrl}/pause`);
    expect(paused.ok(), await paused.text()).toBe(true);

    const deadline = Date.now() + 3 * 60_000;
    while (task(r, "stage").status !== "succeeded" && Date.now() < deadline) {
      await new Promise((res) => setTimeout(res, 2000));
      r = await getRun(request, workspace, submitted.id);
    }
    await new Promise((res) => setTimeout(res, 5000));
    r = await getRun(request, workspace, submitted.id);
    expect(items(r, "fan").filter((i) => i.status === "running" || i.status === "succeeded"), describe(r)).toHaveLength(0);

    const resumed = await request.put(`${runUrl}/resume`);
    expect(resumed.ok(), await resumed.text()).toBe(true);
    const finished = await waitForRun(request, workspace, submitted.id, 3 * 60_000);
    expect(finished.status, describe(finished)).toBe("succeeded");
    expect(items(finished, "fan").map((i) => i.status)).toEqual(["succeeded", "succeeded", "succeeded"]);
  });
});
