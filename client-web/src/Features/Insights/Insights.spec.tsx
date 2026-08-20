import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import queryString, { StringifyOptions } from "query-string";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "ApiServer/msw/node";
import { WorkspaceContainer } from "Features/App/App";
import { serviceUrl } from "Config/servicesConfig";
import WorkflowInsights, { loader } from "./Insights";

vi.mock("@carbon/charts-react", () => ({
  DonutChart: () => <div>DonutChart</div>,
  LineChart: () => <div>LineChart</div>,
  ScatterChart: () => <div>ScatterChart</div>,
}));

vi.mock("@carbon/charts/interfaces", () => ({
  Alignments: {},
  LegendPositions: {},
  ScaleTypes: {},
}));

const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };
const WORKSPACE = "tyson-workspace"; // matches ApiServer/fixtures/workspaces.js content[0] (setupTests.tsx's default workspace).

// Route-module test pattern - see Activity.spec.tsx/GlobalParameters.spec.tsx. Wraps the same
// WorkspaceContainer app/routes/insights.tsx does, since the header's workspace object
// (displayName/breadcrumb) is still a client-side context concern - the loader itself only ever
// needs the `:workspace` URL param (see Insights.tsx's module doc).
function renderInsights(route: string = `/${WORKSPACE}/insights`) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/insights"
      loader={loader}
      element={
        <WorkspaceContainer>
          <WorkflowInsights />
        </WorkspaceContainer>
      }
    />,
    { route },
  );
}

describe("WorkflowInsights --- Snapshot", () => {
  it("Capturing Snapshot of WorkflowInsights", async () => {
    const { baseElement } = renderInsights();
    await screen.findByTestId("completed-insights");
    // eslint-disable-next-line testing-library/no-node-access
    const a11yElement = baseElement.querySelector("#a11y-status-message");
    if (baseElement.contains(a11yElement)) {
      // eslint-disable-next-line testing-library/no-node-access
      a11yElement?.parentNode?.removeChild(a11yElement);
    }
    expect(baseElement).toMatchSnapshot();
  });
});

describe("WorkflowInsights --- RTL", () => {
  test("filtering by status updates the URL search params", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by status/i }));
    userEvent.click(screen.getAllByText("Failed")[0]);

    await waitFor(() =>
      expect(history.location.search).toBe("?" + queryString.stringify({ statuses: "failed" }, queryStringOptions)),
    );
  });

  test("filtering by workflow updates the URL search params", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by Workflow/i }));
    userEvent.click(await screen.findByText("Personal - Java - Deploy"));

    await waitFor(() =>
      expect(history.location.search).toBe(
        "?" + queryString.stringify({ workflows: "Personal - Java - Deploy", page: 0 }, queryStringOptions),
      ),
    );
  });
});

describe("WorkflowInsights --- loader error handling", () => {
  test("does not throw when a fetch fails - surfaces per-source error flags instead", async () => {
    // The loader must swallow each fetch's failure individually (errorLoadingInsights/
    // errorLoadingWorkflows) rather than throw, per CLAUDE.md's "Loaders must not throw" rule.
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), () => HttpResponse.json({}, { status: 500 })),
      http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );
    const request = new Request(`http://localhost/${WORKSPACE}/insights`);

    const data = await loader({ params: { workspace: WORKSPACE }, request });

    expect(data.errorLoadingInsights).toBe(true);
    expect(data.errorLoadingWorkflows).toBe(true);
    expect(data.insights.runs).toEqual([]);
    expect(data.workflowOptions).toEqual([]);
  });
});
