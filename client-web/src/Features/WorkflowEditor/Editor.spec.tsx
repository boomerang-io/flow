import { vi } from "vitest";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { workspaces } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";
import { editorAction, editorLoader } from "./editorRoute";
import Editor from "./index";

const workspace = workspaces.content[0].name;
// The only entry in ApiServer/fixtures/workflowCompose.js - handlers.ts's getWorkflowCompose
// falls back to it for any unmatched id/name, but pass the real one so the route itself carries
// a real workflow segment instead of the literal string "undefined" (see below).
const workflow = "5e877d1f4bbc6e0001c51e12";

// Mirage's `server.db.loadData(db)` bulk-loaded every fixture collection into the mock server on
// top of the ones `startApiServer()` already seeded from its own `fixtures` config - MSW's store
// (ApiServer/msw/db) is already seeded from the same shared fixtures on every reset, so there's
// nothing extra to load here.
//
// jsdom has no ResizeObserver, which @xyflow/react's canvas relies on to size itself on mount
// (see WorkflowRun.spec.tsx for the same stub) - this spec used to fail earlier, on a broken
// Mirage mock route, before ever reaching the canvas render; MSW's correct routing gets it far
// enough to hit this real, previously-masked jsdom gap instead.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  window.focus = vi.fn();
  global.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
});

/*
 * Route-module test pattern (see GlobalParameters.spec.tsx / Workflows.spec.jsx): build the same
 * shape app/routes/editor.tsx registers - a route carrying loader/action alongside its element,
 * matched on the real "/:workspace/editor/:workflow/*" path so the loader's params resolve - and
 * hand it to rtlContextRouterRender, which uses a <Route> element as-is instead of wrapping it in
 * its usual catch-all, so the loader actually runs. Everything the editor renders now reads that
 * loader's data, so without it the page renders nothing at all.
 */
function renderEditor(route: string) {
  return rtlContextRouterRender(
    <Route path={`${AppPath.Editor}/*`} loader={editorLoader} action={editorAction} element={<Editor />} />,
    { route },
  );
}

describe("Editor --- Snapshot", () => {
  it("Capturing Snapshot of Editor", async () => {
    // `appLink.editorCanvas` takes `{ workspace, workflow }` (see Config/appConfig.ts) - this
    // used to be called with a nonexistent `workflowId` key, which produced a `/undefined/editor/
    // undefined/canvas` route that jsdom/react-router still matched (the `:workspace`/`:workflow`
    // segments just bound to the literal string "undefined"). Untyped .jsx let it slip through,
    // and every render used to crash earlier (Mirage mock gaps, then a missing ResizeObserver
    // stub) before ever reaching a point where that would show up in the output - now that both
    // are fixed, the snapshot would otherwise bake in "undefined" hrefs throughout the nav.
    const { baseElement } = renderEditor(appLink.editorCanvas({ workspace, workflow }));
    await screen.findByText("Editor");
    expect(baseElement).toMatchSnapshot();
  });
});

describe("Editor --- loader", () => {
  test("resolves every read the editor renders from", async () => {
    const result = await editorLoader({
      request: new Request(`http://localhost${appLink.editorCanvas({ workspace, workflow })}`),
      params: { workspace, workflow, "*": "canvas" },
    });

    expect(result.editor.errorLoading).toBe(false);
    expect(result.editor.workflow?.name).toBeTruthy();
    expect(result.editor.workflows?.content.length).toBeGreaterThan(0);
    expect(result.editor.changeLog?.length).toBeGreaterThan(0);
    expect(result.editor.availableParameters?.length).toBeGreaterThan(0);
    expect(result.editor.tasks?.content.length).toBeGreaterThan(0);
    expect(result.editor.workspaceTasks?.content.length).toBeGreaterThan(0);
    // The Configure > Tokens tab is served by the same loader (it composes workflowTokensLoader).
    expect(result.tokenSection).toBeDefined();
  });

  // The schedules/calendar pair is the one conditional read: it costs two round trips and only
  // the Schedules tab renders it, so the loader gates it on the route's splat.
  test("skips the schedules/calendar reads off the Schedules tab", async () => {
    const result = await editorLoader({
      request: new Request(`http://localhost${appLink.editorCanvas({ workspace, workflow })}`),
      params: { workspace, workflow, "*": "canvas" },
    });

    expect(result.editor.schedule).toBeNull();
  });

  test("resolves schedules and their calendar on the Schedules tab", async () => {
    const result = await editorLoader({
      request: new Request(`http://localhost${appLink.editorSchedule({ workspace, workflow })}`),
      params: { workspace, workflow, "*": "schedule" },
    });

    expect(result.editor.schedule?.errorLoadingSchedules).toBe(false);
    expect(result.editor.schedule?.errorLoadingCalendar).toBe(false);
    expect(result.editor.schedule?.schedulesData?.content.length).toBeGreaterThan(0);
    expect(result.editor.schedule?.calendarEntries.length).toBeGreaterThan(0);
  });
});

describe("Editor --- action", () => {
  // Driving this through the header's modal fights Carbon's portal/aria-hidden handling in jsdom
  // rather than testing the route's action, so this exercises the action function itself - as
  // `<Route action={action}>` does on submit - against the same mock server the loader tests use.
  test("creates a new version from the createRevision intent", async () => {
    const request = new Request(`http://localhost${appLink.editorCanvas({ workspace, workflow })}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "createRevision",
        revision: JSON.stringify({ name: "my-workflow", changelog: { reason: "Update workflow" } }),
      }),
    });

    const result = await editorAction({ request, params: { workspace, workflow } });

    expect(result.ok).toBe(true);
    expect(result.intent).toBe("createRevision");
  });

  // The editor route has one action serving two unrelated groups of write sites, so an intent it
  // does not own has to reach the token half rather than being read as a revision payload.
  test("routes a token intent to the token action rather than the revision branch", async () => {
    const request = new Request(`http://localhost${appLink.editorConfigureTokens({ workspace, workflow })}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete" }),
    });

    const result = await editorAction({ request, params: { workspace, workflow } });

    expect(result.intent).toBe("delete");
  });
});
