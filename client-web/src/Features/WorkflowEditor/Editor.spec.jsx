import { vi } from "vitest";
import Editor from "./index";
import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { workspaces } from "ApiServer/fixtures";
import { AppPath, appLink } from "Config/appConfig";

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
  global.ResizeObserver = ResizeObserverStub;
});

describe("Editor --- Snapshot", () => {
  it("Capturing Snapshot of Editor", async () => {
    // `appLink.editorCanvas` takes `{ workspace, workflow }` (see Config/appConfig.ts) - this
    // used to be called with a nonexistent `workflowId` key, which produced a `/undefined/editor/
    // undefined/canvas` route that jsdom/react-router still matched (the `:workspace`/`:workflow`
    // segments just bound to the literal string "undefined"). Untyped .jsx let it slip through,
    // and every render used to crash earlier (Mirage mock gaps, then a missing ResizeObserver
    // stub) before ever reaching a point where that would show up in the output - now that both
    // are fixed, the snapshot would otherwise bake in "undefined" hrefs throughout the nav.
    const { baseElement } = rtlContextRouterRender(
      <Route path={`${AppPath.Editor}/*`} element={<Editor />} />,
      { route: appLink.editorCanvas({ workspace, workflow }) }
    );
    await screen.findByText("Editor");
    expect(baseElement).toMatchSnapshot();
  });
});
