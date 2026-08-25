import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { loader } from "./Actions";

const WORKSPACE = "tyson-workspace"; // matches ApiServer/fixtures/workspaces.js content[0].

// Loader-only spec (no render): Actions.tsx's component pulls in the approvals/manual-task tables
// and their modals, none of which this file's assertions are about. The loader is a plain function
// of { params, request }, so it is called directly - the same pattern Schedules.spec.tsx and
// WorkspaceDetailed.spec.tsx use for actions.
function request(search = "") {
  return new Request(`http://localhost/${WORKSPACE}/actions/approvals${search}`);
}

describe("Actions --- loader", () => {
  // None of the four reads depends on another: the two summaries, the table and the workflow
  // filter options are separate endpoints. The loader blocks first paint (there is no per-query
  // pending UI left, and useNavigation() is used nowhere in the app), so a waterfall here is four
  // round trips of blank screen.
  test("fires its four independent reads in one wave, not as a waterfall", async () => {
    const trace = createRequestTrace();
    server.use(
      // Hit twice - today's summary and the filtered summary behind the tab labels - so this
      // resolver records two start/end pairs of its own.
      http.get(serviceUrl.workspace.action.getActionsSummary({ workspace: ":workspace" }), trace.resolver("summary", {})),
      http.get(
        serviceUrl.workspace.action.getActions({ workspace: ":workspace" }),
        trace.resolver("actions", { number: 0, size: 10, totalElements: 0, content: [] }),
      ),
      http.get(
        serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }),
        trace.resolver("workflows", { content: [] }),
      ),
    );

    await loader({ params: { workspace: WORKSPACE, "*": "approvals" }, request: request() });

    expect(trace.startedTogether(4)).toBe(true);
  });

  // The "today's numbers" window was a pair of module-scope `moment()` constants. This module is
  // imported ONCE into a long-lived Node server under ssr:true, so every request reused the window
  // computed at process boot. editorRoute.ts already computes its window per request and says why.
  test("computes the today's-numbers window per request, not at module load", async () => {
    const captured: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.workspace.action.getActionsSummary({ workspace: ":workspace" }), ({ request }) => {
        const fromDate = new URL(request.url).searchParams.get("fromDate");
        // The filtered summary (tab labels) sends no fromDate unless the user picked one.
        if (fromDate) captured.push(fromDate);
        return HttpResponse.json({});
      }),
    );

    const run = () => loader({ params: { workspace: WORKSPACE, "*": "approvals" }, request: request() });

    // src/setupTests.tsx freezes the global clock (vi.setSystemTime, no fake timers) - move it and
    // put it back. Timers themselves stay real, which msw and axios need to resolve the request.
    try {
      vi.setSystemTime(new Date("2030-01-15T12:00:00.000Z"));
      await run();
      vi.setSystemTime(new Date("2030-03-15T12:00:00.000Z"));
      await run();
    } finally {
      vi.setSystemTime(new Date("2020-01-01T00:00:00.000Z"));
    }

    expect(captured).toHaveLength(2);
    expect(captured[0]).not.toBe(captured[1]);
  });

  test("keeps each read's failure independent rather than throwing", async () => {
    server.use(
      http.get(serviceUrl.workspace.action.getActions({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const data = await loader({ params: { workspace: WORKSPACE, "*": "approvals" }, request: request() });

    expect(data.errorLoadingActionsTable).toBe(true);
    expect(data.actionsTable).toBeNull();
    // The other three still resolved.
    expect(data.errorLoadingWorkflows).toBe(false);
  });
});
