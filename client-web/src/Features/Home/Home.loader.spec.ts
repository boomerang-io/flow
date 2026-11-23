import { vi } from "vitest";
import { delay, http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { loader as rootLoader } from "Features/App/App";
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
  // 22:30 UTC on Thursday 5 November is Friday 6 November in Sydney (UTC+11). One run falls on the
  // 5th there, two on the 6th; every one of them is on the 5th in UTC.
  test("counts today's runs in the viewer's day, read from the time zone the page stored", async () => {
    const runStarts = ["2026-11-05T12:00:00.000Z", "2026-11-05T14:00:00.000Z", "2026-11-05T22:00:00.000Z"];
    server.use(
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace: ":workspace" }), ({ request }) => {
        const url = new URL(request.url);
        const from = Number(url.searchParams.get("fromDate"));
        const to = Number(url.searchParams.get("toDate"));
        const succeeded = runStarts.filter((start) => Date.parse(start) >= from && Date.parse(start) <= to).length;
        return HttpResponse.json({ status: { all: succeeded, succeeded } });
      }),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => HttpResponse.json(emptyPage)),
    );
    const frozen = new Date();
    vi.setSystemTime(new Date("2026-11-05T22:30:00.000Z"));
    try {
      const sydney = await loader({
        request: new Request("http://localhost/home", { headers: { Cookie: "session=abc; flow-tz=Australia%2FSydney" } }),
      });
      expect(sydney.timeZone).toBe("Australia/Sydney");
      expect(sydney.runsToday.all).toBe(2 * WORKSPACE_COUNT);

      // No stored zone, or one moment does not know: the server's day, here UTC.
      const serverDay = await loader({ request: new Request("http://localhost/home", { headers: { Cookie: "flow-tz=Nowhere%2FAtAll" } }) });
      expect(serverDay.timeZone).toBeNull();
      expect(serverDay.runsToday.all).toBe(3 * WORKSPACE_COUNT);
    } finally {
      vi.setSystemTime(frozen);
    }
  });

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
    expect(data.degraded).toEqual({ runsToday: false, attention: false, schedules: false, recentRuns: false });
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

  test("marks only the failed part, instead of throwing, when one workspace's read fails", async () => {
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

    expect(data.degraded).toEqual({ runsToday: true, attention: false, schedules: false, recentRuns: false });
    expect(data.runsToday.all).toBe(WORKSPACE_COUNT - 1);
    // No stats rather than zeros, so the workspace card shows its count as unknown.
    expect(data.stats.system).toBeUndefined();
  });

  test("renders an empty rollup, not an error, when the profile cannot be read", async () => {
    server.use(http.get(serviceUrl.getUserProfile(), () => HttpResponse.json({}, { status: 500 })));

    const data = await loader({ request: request() });

    expect(data.degraded).toEqual({ runsToday: true, attention: true, schedules: true, recentRuns: true });
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

// A document request runs the root loader and Home's loader side by side, and both need the
// profile. They share the one read; a later load still reads it again, so Home never shows a
// stale profile.
describe("Home --- profile read", () => {
  function countProfileReads() {
    const reads = { count: 0 };
    server.use(
      http.get(serviceUrl.getUserProfile(), async () => {
        reads.count += 1;
        await delay(20);
        return HttpResponse.json({ id: "user-1", workspaces: [] });
      }),
      http.get(serviceUrl.getFeatureFlags(), () => HttpResponse.json({ features: {} })),
      http.get(serviceUrl.getContext(), () => HttpResponse.json({})),
      http.get(serviceUrl.getNavigation({ query: "" }), () => HttpResponse.json([])),
    );
    return reads;
  }

  test("reads the profile once for the root loader and Home's loader together", async () => {
    const reads = countProfileReads();
    const cookieRequest = () => new Request("http://localhost/home", { headers: { Cookie: "session=abc" } });

    await Promise.all([rootLoader({ request: cookieRequest() }), loader({ request: cookieRequest() })]);

    expect(reads.count).toBe(1);
  });

  test("never shares a read between two callers", async () => {
    const seen: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.getUserProfile(), async ({ request: profileRequest }) => {
        const cookie = profileRequest.headers.get("cookie");
        seen.push(cookie);
        await delay(20);
        return HttpResponse.json({ id: cookie, workspaces: [] });
      }),
      http.get(serviceUrl.getFeatureFlags(), () => HttpResponse.json({ features: {} })),
      http.get(serviceUrl.getContext(), () => HttpResponse.json({})),
      http.get(serviceUrl.getNavigation({ query: "" }), () => HttpResponse.json([])),
    );
    const as = (cookie: string) => new Request("http://localhost/home", { headers: { Cookie: cookie } });

    const [one, two] = await Promise.all([
      rootLoader({ request: as("session=one") }),
      rootLoader({ request: as("session=two") }),
    ]);

    expect(seen.sort()).toEqual(["session=one", "session=two"]);
    expect(one.user?.id).toBe("session=one");
    expect(two.user?.id).toBe("session=two");
  });

  // With no Cookie header there is no caller to tell apart: service-core is sent the same request
  // by both, and answers both the same (a 401, or the one identity it serves with security off).
  test("shares a read between two requests that carry no cookie", async () => {
    const reads = countProfileReads();
    const anonymous = () => new Request("http://localhost/home");

    const [one, two] = await Promise.all([rootLoader({ request: anonymous() }), rootLoader({ request: anonymous() })]);

    expect(reads.count).toBe(1);
    expect(one.user?.id).toBe("user-1");
    expect(two.user?.id).toBe("user-1");
  });

  test("reads it again on a later load", async () => {
    const reads = countProfileReads();

    await loader({ request: request() });
    await loader({ request: request() });

    expect(reads.count).toBe(2);
  });
});
