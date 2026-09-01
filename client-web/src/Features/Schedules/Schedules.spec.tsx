import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { db } from "ApiServer/msw/db";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { serviceUrl } from "Config/servicesConfig";
import { scheduleAction } from "./scheduleRoute";
import Schedules, { loader } from "./Schedules";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

// Route-module test pattern - see GlobalParameters.spec.tsx/WorkspaceTasks.spec.tsx. Schedules
// reads the active workspace off its context for the header/breadcrumb, so the harness's
// WorkspaceContextProvider is overridden with the full workspace fixture this route's WORKSPACE
// constant names - production supplies it from app/routes/workspaceLayout.tsx's loader.
function renderSchedules(route: string = `/${WORKSPACE}/schedules`) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/schedules"
      loader={loader}
      action={scheduleAction}
      element={<Schedules />}
    />,
    { route, workspaceValue: { workspace: workspaceFixture } },
  );
}

// react-big-calendar registers a document-level mousedown listener (Selection.js) that calls
// document.elementFromPoint on EVERY click while the calendar is mounted. jsdom doesn't
// implement it, and each resulting uncaught TypeError is an unhandled error that fails the file
// (vitest exits non-zero) even with every test green - see the same stub in
// WorkflowEditor/Schedule/Schedule.spec.tsx.
beforeAll(() => {
  if (typeof document.elementFromPoint !== "function") {
    document.elementFromPoint = () => null;
  }
});

describe("Schedules --- loader", () => {
  test("renders the schedules resolved by the loader", async () => {
    renderSchedules();
    expect(await screen.findByText("Trigger")).toBeInTheDocument();
    expect(screen.getByText("Daily event")).toBeInTheDocument();
  });

  // ScheduleStatus gained "completed" (a runOnce schedule moves there once it fires) - the
  // default (no filter selected) status list needs to keep including it, otherwise completed
  // schedules would silently vanish from the page on first load. See scheduleStatusOptions in
  // Constants/index.ts, which this default derives from.
  test("defaults the status filter query to include the newly-added completed status", async () => {
    let capturedSearch = "";
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), ({ request }) => {
        capturedSearch = new URL(request.url).search;
        return HttpResponse.json({ content: [] });
      }),
    );

    await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/schedules`),
    });

    expect(capturedSearch).toContain("completed");
  });

  // The calendar query is genuinely dependent on the schedules query (it needs the resolved
  // schedule ids) - the loader sequences it as an explicit `await` after schedules resolve rather
  // than firing both in parallel, mirroring the previous client-side `enabled: hasScheduleData`.
  test("skips the calendar fetch entirely when there are no schedules", async () => {
    let calendarRequests = 0;
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () =>
        HttpResponse.json({ content: [] }),
      ),
      http.get(serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace: ":workspace" }), () => {
        calendarRequests += 1;
        return HttpResponse.json([]);
      }),
    );

    const data = await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/schedules`),
    });

    expect(calendarRequests).toBe(0);
    expect(data.calendarEntries).toEqual([]);
  });

  test("fetches the calendar, sequenced after schedules, once schedules exist", async () => {
    const data = await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/schedules`),
    });

    expect(data.schedulesData?.content.length).toBeGreaterThan(0);
    expect(data.calendarEntries.length).toBeGreaterThan(0);
  });

  // The loader has always computed errorLoadingSchedules/errorLoadingWorkflows, but the component
  // destructured neither, so a failed read reached the user as an ordinary empty page - "no
  // schedules", which reads as "your schedules were deleted". Same convention as Activity and
  // Insights: page chrome plus an explicit error, never a silently empty list.
  test("renders an error, not an empty schedule list, when the schedules fetch fails", async () => {
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    renderSchedules();

    expect(await screen.findByText("Oops, something went wrong.")).toBeInTheDocument();
    expect(screen.queryByText("Daily event")).not.toBeInTheDocument();
  });

  test("renders an error when the workflows fetch fails", async () => {
    server.use(
      http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    renderSchedules();

    expect(await screen.findByText("Oops, something went wrong.")).toBeInTheDocument();
  });

  // A failed calendar fetch is a partial failure - the schedule list is still accurate - so it
  // surfaces next to the calendar rather than replacing the page. It used to be piped into a
  // `data-is-loading` attribute on the calendar container, which showed the user nothing.
  test("surfaces a failed calendar fetch beside the still-valid schedule list", async () => {
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    renderSchedules();

    expect(await screen.findByText("Calendar unavailable")).toBeInTheDocument();
    expect(screen.getByText("Daily event")).toBeInTheDocument();
  });

  // The workflows read and the schedules read have no data dependency on each other (only the
  // calendar depends on schedules), so they belong in one wave - the loader blocks first paint.
  test("fires the workflows and schedules reads in one wave, not as a waterfall", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(
        serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }),
        trace.resolver("workflows", { content: [] }),
      ),
      http.get(
        serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }),
        trace.resolver("schedules", { content: [] }),
      ),
    );

    await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/schedules`),
    });

    expect(trace.startedTogether(2)).toBe(true);
  });

  // The calendar window defaulted to a pair of module-scope `moment()` constants. This module is
  // imported ONCE into a long-lived Node server under ssr:true, so every request reused the month
  // the process happened to boot in. editorRoute.ts's loadSchedule already computes its window per
  // request and says why.
  test("computes the default calendar month per request, not at module load", async () => {
    const captured: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace: ":workspace" }), ({ request }) => {
        captured.push(new URL(request.url).searchParams.get("fromDate"));
        return HttpResponse.json([]);
      }),
    );

    const run = () =>
      loader({ params: { workspace: WORKSPACE }, request: new Request(`http://localhost/${WORKSPACE}/schedules`) });

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

  test("does not throw when the schedules fetch fails, so the route chrome still renders", async () => {
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const data = await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/schedules`),
    });

    expect(data.errorLoadingSchedules).toBe(true);
    expect(data.schedulesData).toBeUndefined();
  });

  // The write path from THIS page (the other consumer of the shared components is exercised in
  // WorkflowEditor/Schedule/Schedule.spec.tsx): disabling a schedule submits the toggleSchedule
  // intent to this route's action, and the fetcher's settle re-runs the loader on its own -
  // pinning that the removed revalidator.revalidate() calls were genuinely redundant.
  test("disabling a schedule PUTs through the route action and auto-revalidates the loader", async () => {
    let schedulesRequests = 0;
    server.use(
      http.get(serviceUrl.workspace.schedule.getSchedules({ workspace: ":workspace" }), () => {
        schedulesRequests += 1;
        return HttpResponse.json({ content: db.schedules });
      }),
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), async ({ request }) => {
        const body = (await request.json()) as { id: string } & Record<string, unknown>;
        const index = db.schedules.findIndex((s) => s.id === body.id);
        db.schedules[index] = { ...db.schedules[index], ...body };
        return HttpResponse.json(db.schedules[index]);
      }),
    );

    renderSchedules();

    await screen.findByText("Trigger");
    const initialSchedulesRequests = schedulesRequests;

    // Cards sort by name (Daily event, Deleted Daily event, Trigger) - index 2 is "Trigger",
    // the one active schedule, whose menu therefore offers "Disable". The menu button's
    // accessible name is its iconDescription tooltip (see SchedulePanelList.spec.tsx).
    await userEvent.click(screen.getAllByRole("button", { name: "Schedule menu icon" })[2]);
    await userEvent.click(await screen.findByText("Disable"));

    expect(await screen.findByText("Disable Schedule?")).toBeInTheDocument();
    const disableButtons = screen.getAllByRole("button", { name: "Disable", hidden: true });
    await userEvent.click(disableButtons[disableButtons.length - 1]);

    // The PUT landed (fixture flipped in the mock db)...
    await waitFor(() =>
      expect(db.schedules.find((s) => s.id === "61d6286bc570b75ec2b47884")?.status).toBe("inactive"),
    );
    // ...and the loader re-ran without any manual revalidate call.
    await waitFor(() => expect(schedulesRequests).toBeGreaterThan(initialSchedulesRequests));
  });
});
