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
 * The no-Kubernetes quickstart proved end to end: a REAL dispatcher running `dispatcher.executor=docker`
 * against the host's Docker daemon, so each task is a sibling container of the stack itself. Bring the
 * stack up with docker-compose.yml + docker-compose.docker.yml, then set E2E_DOCKER_DISPATCHER=true - the
 * base stack carries no dispatcher, so this file is skipped without it.
 *
 * Same shape as tests/dispatcher-kube.spec.ts and the same support/dispatcher helpers: each scenario
 * creates its own workflow in one shared workspace, submits it with start=true and asserts the terminal
 * run. What differs is the runtime being asserted - a Docker volume rather than a persistent volume claim,
 * and `docker volume ls` rather than kubectl.
 */

const ENABLED = process.env.E2E_DOCKER_DISPATCHER === "true";

test.describe("dispatcher on docker", () => {
  test.skip(!ENABLED, "set E2E_DOCKER_DISPATCHER=true against a stack that runs the docker executor");
  test.describe.configure({ mode: "parallel", timeout: 6 * 60_000 });

  let workspace: string;

  test.beforeAll(async ({ request }) => {
    workspace = (await createWorkspace(request, uniqueName("e2e-docker"))).name;
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

  /** The volume names carrying a workspace-ref label, as `docker volume ls` reports them. */
  function workspaceVolumes(workspaceRef: string): string[] {
    const out = execSync(
      `docker volume ls --filter label=boomerang.io/workspace-ref=${workspaceRef} --format "{{.Name}}"`,
      { encoding: "utf8" },
    ).trim();
    return out ? out.split("\n") : [];
  }

  /*
   * Run-scoped storage is released by the dispatcher reconciling the host against the engine (there is
   * no phase after completed that says "released"), so the volume survives the run and disappears
   * within one reconcile tick of it - FLOW_DISPATCHER_WORKSPACE_RECONCILE_MS is 10 s on the overlay.
   */
  async function expectWorkspaceVolumeReleased(workspaceRef: string, timeoutMs = 60_000) {
    const deadline = Date.now() + timeoutMs;
    let volumes = workspaceVolumes(workspaceRef);
    while (volumes.length && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 2000));
      volumes = workspaceVolumes(workspaceRef);
    }
    expect(volumes, `run-scoped volume for ${workspaceRef} released within one reconcile interval`).toEqual([]);
  }

  const start = { name: "start", type: "start" };
  // A shell task on the catalogue's execute-shell template - the same task the Kubernetes scenarios use,
  // so what is being compared between runtimes is the runtime and nothing else. Results are written by
  // the script to $RESULTS_PATH, which on Docker is one file on the container's own writable layer,
  // copied out after it exits.
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
  const endAfter = (...deps: string[]) => ({
    name: "end",
    type: "end",
    dependencies: deps.map((taskRef) => ({ taskRef })),
  });
  // An omitted executionCondition means `always`, which lets a failed task's successors run and the run
  // then succeed; scenarios asserting failure propagation gate `end` on `success`.
  const endOnSuccessOf = (...deps: string[]) => ({
    name: "end",
    type: "end",
    dependencies: deps.map((taskRef) => ({ taskRef, executionCondition: "success" })),
  });

  test("a catalogue task runs in a container and the run succeeds", async ({ request }) => {
    const { run: r } = await run(request, {
      tasks: [start, shellTask("greet", "echo hello-from-docker"), endAfter("greet")],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    expect(task(r, "greet").status, describe(r)).toBe("succeeded");
    const log = await taskLog(request, task(r, "greet").id);
    expect(log, "task log should stream the container output").toContain("hello-from-docker");
  });

  test("a declared parameter arrives in the container as PARAM_<NAME>", async ({ request }) => {
    // The Docker executor builds its env from the same KubeHelperService.createTaskEnvVars the
    // Kubernetes runtimes use, so the params-in contract must be identical.
    const script =
      `echo "greeting=[\${PARAM_GREETING}]"\n` +
      `echo '{"echoed":"'"$PARAM_GREETING"'"}' > "$RESULTS_PATH"`;
    const { run: r } = await run(request, {
      tasks: [
        start,
        {
          name: "read-param",
          type: "script",
          taskRef: "execute-shell",
          params: [
            { name: "shell", value: "sh" },
            { name: "greeting", value: "hello-param" },
            { name: "script", value: script },
          ],
          results: [{ name: "echoed", description: "the value PARAM_GREETING held in the container" }],
          dependencies: [{ taskRef: "start" }],
        },
        endAfter("read-param"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    const t = task(r, "read-param");
    expect(t.status, describe(r)).toBe("succeeded");
    expect(result(t, "echoed"), describe(r)).toBe("hello-param");
    const log = await taskLog(request, t.id);
    expect(log, "the declared param should reach the container under its PARAM_ name").toContain(
      "greeting=[hello-param]",
    );
  });

  test("a result written to RESULTS_PATH comes back on the task run", async ({ request }) => {
    // On Docker RESULTS_PATH is /results.json on the container's writable layer, read with a copy-out
    // from the EXITED container - a path the Kubernetes runtimes never exercise.
    const { run: r } = await run(request, {
      tasks: [
        start,
        shellTask("extract", `${writeResult("greeting", "hello from docker")}; cat "$RESULTS_PATH"`, {
          results: [{ name: "greeting", description: "written to RESULTS_PATH by the script" }],
        }),
        endAfter("extract"),
      ],
    });
    expect(r.status, describe(r)).toBe("succeeded");
    const t = task(r, "extract");
    expect(t.status, describe(r)).toBe("succeeded");
    expect(result(t, "greeting"), describe(r)).toBe("hello from docker");
  });

  test("a run-scoped workspace is a labelled docker volume, released after the run", async ({ request }) => {
    const mount = "/workspace/run";
    const ws = [{ name: "run-store", type: "workflowrun", mountPath: mount }];
    const wf = await createWorkflowFromSpec(request, workspace, {
      name: uniqueName("wf-ws"),
      workspaces: [
        {
          name: "run-store",
          type: "workflowrun",
          optional: false,
          // size, accessMode and className are recorded on the run and ignored by a local volume.
          spec: { size: "1Gi", accessMode: "ReadWriteOnce", mountPath: mount },
        },
      ],
      tasks: [
        start,
        shellTask("write", `mkdir -p ${mount} && echo hello-storage > ${mount}/hello.txt && ls -la ${mount}`, {
          workspaces: ws,
        }),
        shellTask(
          "check",
          `cat ${mount}/hello.txt && grep -q hello-storage ${mount}/hello.txt && ${writeResult("seen", "yes")}`,
          { workspaces: ws, results: [{ name: "seen" }] },
          [{ taskRef: "write" }],
        ),
        endAfter("check"),
      ],
    });
    const submitted = await submitAndStart(request, workspace, wf.name);

    // The volume is provisioned before the run starts and is labelled with the run id.
    let volumes: string[] = [];
    for (let i = 0; i < 60 && !volumes.length; i++) {
      volumes = workspaceVolumes(submitted.id);
      if (!volumes.length) await new Promise((r) => setTimeout(r, 2000));
    }
    expect(volumes, `a docker volume labelled boomerang.io/workspace-ref=${submitted.id}`).toHaveLength(1);
    expect(volumes[0], "named for the run-scoped workspace").toMatch(
      new RegExp(`-ws-workflowrun-${submitted.id}$`, "i"),
    );

    const r = await waitForRun(request, workspace, submitted.id);
    // succeeded is terminal - completed is the run's last phase and nothing follows it.
    expect(r.status, describe(r)).toBe("succeeded");
    expect(task(r, "check").status, describe(r)).toBe("succeeded");
    expect(result(task(r, "check"), "seen"), describe(r)).toBe("yes");
    // The volume is keyed by the run id and is the dispatcher's to release once the run is done.
    await expectWorkspaceVolumeReleased(r.id);
  });

  test("a task that exits non-zero ends the run failed with a typed reason", async ({ request }) => {
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
    // The closed statusReason set (decision 0065); a plain non-zero exit is JobFailed on every runtime.
    expect(t.statusReason, describe(r)).toBe("JobFailed");
  });
});
