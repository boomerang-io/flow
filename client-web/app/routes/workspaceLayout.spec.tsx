import React from "react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { useWorkspaceContext } from "State/context";
import WorkspaceLayoutRoute, { loader } from "./workspaceLayout";

// Matches ApiServer/fixtures/workspaces.js content[0] (setupTests.tsx's default workspace).
const WORKSPACE = "tyson-workspace";

// Reads the workspace exactly the way the ~50 existing consumers do - through the UNCHANGED
// useWorkspaceContext() signature - so a pass here proves the layout feeds the same provider
// WorkspaceContainer used to, with no consumer rewiring.
function WorkspaceProbe() {
  const { workspace } = useWorkspaceContext();
  return <p>probe sees {workspace.name}</p>;
}

// Nested-route test setup per Configure.spec.tsx's worked example: the layout route mounts with
// its real loader and a child route stands in for the 12 workspace-scoped screens. The loader
// resolves against the shared MSW handlers (resourceWorkspace GET), same as production.
function renderLayout(route: string = `/${WORKSPACE}/probe`) {
  return global.rtlContextRouterRender(
    <Route path="/:workspace" loader={loader} element={<WorkspaceLayoutRoute />}>
      <Route path="probe" element={<WorkspaceProbe />} />
    </Route>,
    { route },
  );
}

describe("workspaceLayout route", () => {
  it("a child route sees the loader-fetched workspace through the unchanged context", async () => {
    renderLayout();
    expect(await screen.findByText(`probe sees ${WORKSPACE}`)).toBeInTheDocument();
  });

  it("an unknown workspace slug renders the catch-all's not-found, not a blank page", async () => {
    // The shared handler 404s for a slug findWorkspace can't resolve - no override needed.
    renderLayout("/no-such-workspace/probe");
    expect(await screen.findByText(/404/)).toBeInTheDocument();
    expect(screen.queryByText(/probe sees/)).not.toBeInTheDocument();
  });

  it("a failed workspace fetch renders a real error, never null (the old blank content area)", async () => {
    server.use(
      http.get(serviceUrl.resourceWorkspace({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    renderLayout();
    expect(await screen.findByText("Oops!")).toBeInTheDocument();
    expect(screen.queryByText(/probe sees/)).not.toBeInTheDocument();
  });
});
