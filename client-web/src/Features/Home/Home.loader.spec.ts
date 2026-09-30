import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { ATTENTION_LIMIT, RECENT_RUNS_LIMIT, loader } from "./homeLoader";

// Loader-only spec (no render), the same shape as Actions.loader.spec.ts. The profile fixture
// (ApiServer/fixtures/profile.js) carries three workspaces, so every per-workspace read below is
// hit three times.
const WORKSPACE_COUNT = 3;

function request() {
  return new Request("http://localhost/home");
}

const emptyPage = { totalElements: 0, content: [] };

function run(overrides: Partial<{ id: string; creationDate: string; status: string; workflowName: string }> = {}) {
  return {
    id: "run-1",
    creationDate: "2024-01-01T00:00:00.000Z",
    status: "succeeded",
    workflowName: "wf",
    workflowRef: "wf-ref",
    trigger: "manual",
    duration: 1000,
    ...overrides,
  };
}

describe("Home --- loader", () => {
  test("fires every workspace's four reads in one wave, not as a waterfall", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(
        serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }),
        trace.resolver("count", { status: { all: 2, succeeded: 1, failed: 1 } }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), trace.resolver("runs", emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), trace.resolver("actions", emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), trace.resolver("schedules", emptyPage)),
    );

    const data = await loader({ request: request() });

    expect(trace.startedTogether(WORKSPACE_COUNT * 4)).toBe(true);
    expect(data.runsToday).toEqual({ all: 6, succeeded: 3, failed: 3, running: 0, waiting: 0 });
    expect(Object.keys(data.stats)).toHaveLength(WORKSPACE_COUNT);
    expect(data.degraded).toBe(false);
  });

  test("merges runs across workspaces newest first and caps the list", async () => {
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), () =>
        HttpResponse.json({ status: {} }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), ({ params }) => {
        const workspace = String(params.workspace);
        // The "system" workspace holds the newest run; every workspace reports 4 runs ever.
        const newest = workspace === "system" ? "2024-06-01T00:00:00.000Z" : "2024-01-01T00:00:00.000Z";
        return HttpResponse.json({
          totalElements: 4,
          content: [run({ id: `${workspace}-a`, creationDate: newest }), run({ id: `${workspace}-b` }), run({ id: `${workspace}-c` })],
        });
      }),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
    );

    const data = await loader({ request: request() });

    expect(data.recentRuns).toHaveLength(RECENT_RUNS_LIMIT);
    expect(data.recentRuns[0]).toMatchObject({ id: "system-a", workspace: "system", workspaceDisplayName: "System and Administration" });
    expect(data.totalRuns).toBe(4 * WORKSPACE_COUNT);
    expect(data.stats.system.lastRun?.id).toBe("system-a");
  });

  test("collects submitted actions across workspaces and counts them by type", async () => {
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), () =>
        HttpResponse.json({ status: {} }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), ({ request }) => {
        // The loader asks for submitted actions only.
        expect(new URL(request.url).searchParams.get("statuses")).toBe("submitted");
        return HttpResponse.json({
          totalElements: 2,
          content: [
            { id: "approval", type: "approval", status: "submitted", creationDate: "2024-01-02T00:00:00.000Z" },
            { id: "manual", type: "manual", status: "submitted", creationDate: "2024-01-01T00:00:00.000Z" },
          ],
        });
      }),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
    );

    const data = await loader({ request: request() });

    expect(data.attentionTotal).toBe(2 * WORKSPACE_COUNT);
    expect(data.attention).toHaveLength(ATTENTION_LIMIT);
    expect(data.attention[0].workspaceDisplayName).toBeTruthy();
    // Six submitted actions, five shown: three approvals (newest) and two manual tasks.
    expect(data.attentionApprovals).toBe(3);
    expect(data.attentionManual).toBe(2);
  });

  test("picks the earliest next scheduled run across workspaces", async () => {
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), () =>
        HttpResponse.json({ status: {} }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), ({ params }) => {
        const workspace = String(params.workspace);
        return HttpResponse.json({
          totalElements: 2,
          content: [
            { id: `${workspace}-soon`, name: "soon", nextScheduleDate: workspace === "system" ? "2030-01-01T09:00:00Z" : "2030-01-01T12:00:00Z" },
            { id: `${workspace}-none`, name: "no date" },
          ],
        });
      }),
    );

    const data = await loader({ request: request() });

    expect(data.nextSchedule).toMatchObject({ id: "system-soon", workspace: "system" });
    expect(data.schedulesTotal).toBe(2 * WORKSPACE_COUNT);
  });

  test("degrades instead of throwing when one workspace's read fails", async () => {
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), ({ params }) =>
        params.workspace === "system"
          ? HttpResponse.json({}, { status: 500 })
          : HttpResponse.json({ status: { all: 1, succeeded: 1 } }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
    );

    const data = await loader({ request: request() });

    expect(data.degraded).toBe(true);
    expect(data.runsToday.all).toBe(WORKSPACE_COUNT - 1);
    expect(data.stats.system.runsToday.all).toBe(0);
  });

  test("renders an empty rollup, not an error, when the profile cannot be read", async () => {
    server.use(http.get(serviceUrl.getUserProfile(), () => HttpResponse.json({}, { status: 500 })));

    const data = await loader({ request: request() });

    expect(data.degraded).toBe(true);
    expect(data.recentRuns).toEqual([]);
    expect(data.stats).toEqual({});
  });

  // The "today" window must be computed per request: this module is imported once into a
  // long-lived Node server under ssr:true (see Actions.loader.spec.ts for the same guard).
  test("computes the today's-numbers window per request, not at module load", async () => {
    const captured: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), ({ request }) => {
        captured.push(new URL(request.url).searchParams.get("fromDate"));
        return HttpResponse.json({ status: {} });
      }),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
    );

    try {
      vi.setSystemTime(new Date("2030-01-15T12:00:00.000Z"));
      await loader({ request: request() });
      vi.setSystemTime(new Date("2030-03-15T12:00:00.000Z"));
      await loader({ request: request() });
    } finally {
      vi.setSystemTime(new Date("2020-01-01T00:00:00.000Z"));
    }

    expect(captured).toHaveLength(2 * WORKSPACE_COUNT);
    expect(captured[0]).not.toBe(captured[WORKSPACE_COUNT]);
  });
});
