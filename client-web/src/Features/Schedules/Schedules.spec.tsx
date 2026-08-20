import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { db } from "ApiServer/msw/db";
import { workspace as workspaceFixture } from "ApiServer/fixtures";
import { WorkspaceContainer } from "Features/App/App";
import { serviceUrl } from "Config/servicesConfig";
import Schedules, { loader } from "./Schedules";

const WORKSPACE = "ibm-services-engineering"; // matches src/ApiServer/fixtures/workspace.js.

// Route-module test pattern - see GlobalParameters.spec.tsx/WorkspaceTasks.spec.tsx. Wraps
// WorkspaceContainer the same way app/routes/schedules.tsx does, since Schedules reads the active
// workspace off its context for the header/breadcrumb (unrelated to the loader migration).
function renderSchedules(route: string = `/${WORKSPACE}/schedules`) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/schedules"
      loader={loader}
      element={
        <WorkspaceContainer>
          <Schedules />
        </WorkspaceContainer>
      }
    />,
    { route },
  );
}

// WorkspaceContainer resolves the active workspace via `resourceWorkspace`, a real lookup by name
// (handlers.ts's `findWorkspace`) - seed the fixture the WORKSPACE constant names, same as
// WorkspaceTasks.spec.tsx.
beforeEach(() => {
  db.workspaces.push(structuredClone(workspaceFixture));
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
});
