import { http, HttpResponse } from "msw";
import queryString, { StringifyOptions } from "query-string";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "ApiServer/msw/node";
import { WorkspaceContainer } from "Features/App/App";
import { serviceUrl } from "Config/servicesConfig";
import WorkflowActivity, { loader } from "./Activity";

const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };
const WORKSPACE = "tyson-workspace"; // matches ApiServer/fixtures/workspaces.js content[0] (setupTests.tsx's default workspace).

// Route-module test pattern - see GlobalParameters.spec.tsx/WorkspaceTasks.spec.tsx. Wraps the
// same WorkspaceContainer app/routes/activity.tsx does, since the header's workspace object
// (displayName/breadcrumb) is still a client-side context concern - the loader itself only ever
// needs the `:workspace` URL param (see Activity.tsx's module doc).
function renderActivity(route: string = `/${WORKSPACE}/activity`) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/activity"
      loader={loader}
      element={
        <WorkspaceContainer>
          <WorkflowActivity />
        </WorkspaceContainer>
      }
    />,
    { route },
  );
}

const basicQuery = { order: "DESC", page: 0, limit: 10, sort: "creationDate" };

describe("WorkflowActivity --- loader", () => {
  test("renders today's run stats and the run table resolved by the loader", async () => {
    renderActivity();

    // ApiServer/fixtures/workflowRunCount.js: all: 1969, succeeded: 1854, running: 27, waiting: 0 -
    // stick to values that can't collide with the (always-in-the-DOM) date-picker calendar's
    // day-of-month cells (1-31), unlike e.g. the "17" failures count.
    expect(await screen.findByText("1969")).toBeInTheDocument();
    expect(screen.getByText("1854")).toBeInTheDocument();
    // successRate = round((succeeded + inProgress) / all * 100) = round((1854 + 27) / 1969 * 100) = 96.
    expect(screen.getByText("96%")).toBeInTheDocument();

    // ApiServer/fixtures/workflowRuns.js: 10 runs, all status "succeeded".
    expect(screen.getAllByText("Succeeded").length).toBeGreaterThan(0);
  });
});

describe("WorkflowActivity --- RTL", () => {
  test("filtering by trigger updates the URL search params", async () => {
    const { history } = renderActivity();
    await screen.findByText("1969");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by trigger/i }));
    userEvent.click(screen.getAllByText("Manual")[0]);

    await waitFor(() =>
      expect(history.location.search).toBe("?" + queryString.stringify({ triggers: "manual", ...basicQuery }, queryStringOptions)),
    );
  });

  test("filtering by status updates the URL search params", async () => {
    const { history } = renderActivity();
    await screen.findByText("1969");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by status/i }));
    userEvent.click(screen.getAllByText("Failed")[0]);

    await waitFor(() =>
      expect(history.location.search).toBe("?" + queryString.stringify({ statuses: "failed", ...basicQuery }, queryStringOptions)),
    );
  });

  test("filtering by workflow updates the URL search params", async () => {
    const { history } = renderActivity();
    await screen.findByText("1969");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by Workflow/i }));
    userEvent.click(await screen.findByText("Personal - Java - Deploy"));

    await waitFor(() =>
      expect(history.location.search).toBe(
        "?" + queryString.stringify({ workflows: "Personal - Java - Deploy", ...basicQuery }, queryStringOptions),
      ),
    );
  });
});

describe("WorkflowActivity --- loader error handling", () => {
  test("does not throw when a fetch fails - surfaces per-source error flags instead", async () => {
    // The loader must swallow each fetch's failure individually (errorLoadingWorkflows/
    // errorLoadingRuns) rather than throw, per CLAUDE.md's "Loaders must not throw" rule (a
    // thrown loader would replace the whole route with the router's errorElement instead of the
    // page chrome + inline error state the component renders for this).
    server.use(
      http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
      http.get(serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );
    const request = new Request(`http://localhost/${WORKSPACE}/activity`);

    const data = await loader({ params: { workspace: WORKSPACE }, request });

    expect(data.errorLoadingWorkflows).toBe(true);
    expect(data.errorLoadingRuns).toBe(true);
    expect(data.workflowOptions).toEqual([]);
    expect(data.runs.content).toEqual([]);
  });
});
