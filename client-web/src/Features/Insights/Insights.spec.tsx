import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import queryString, { StringifyOptions } from "query-string";
import { Route } from "react-router-dom";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { renderWithContext } from "Utils/testing/render";
import Insights, { loader } from "./Insights";

// The chart library needs a real DOM and layout; what it draws is the fixture's daily counts,
// which the loader tests below cover.
vi.mock("@carbon/charts-react", () => ({
  StackedBarChart: () => <div>StackedBarChart</div>,
}));

vi.mock("@carbon/charts", () => ({
  ScaleTypes: { TIME: "time" },
}));

const queryStringOptions: StringifyOptions = { arrayFormat: "comma", skipEmptyString: true };
const WORKSPACE = "tyson-workspace"; // matches ApiServer/fixtures/workspaces.js content[0] (setupTests.tsx's default workspace).

// Route-module test pattern - see Activity.spec.tsx. The header's workspace object comes from the
// harness's own WorkspaceContextProvider default; production supplies it from
// app/routes/workspaceLayout.tsx's loader. The loader itself only needs the `:workspace` param.
function renderInsights(route: string = `/${WORKSPACE}/insights`) {
  return renderWithContext(<Route path="/:workspace/insights" loader={loader} element={<Insights />} />, { route });
}

describe("Insights --- page", () => {
  test("leads with the period's numbers and their previous-period comparison", async () => {
    renderInsights();
    await screen.findByTestId("completed-insights");

    expect(screen.getByTestId("insights-tile-runs")).toHaveTextContent("103");
    expect(screen.getByTestId("insights-tile-runs")).toHaveTextContent("30% vs previous 90 days (79)");
    expect(screen.getByTestId("insights-tile-success")).toHaveTextContent("90.0%");
    expect(screen.getByTestId("insights-tile-success")).toHaveTextContent("2.3 points vs previous 90 days (92.3%)");
    expect(screen.getByTestId("insights-tile-p50")).toHaveTextContent("1 min 48 s");
    expect(screen.getByTestId("insights-tile-p95")).toHaveTextContent("22 min");
    // The subtitle says where the numbers come from.
    expect(screen.getByText(/computed from the runs this workspace still holds/)).toBeInTheDocument();
  });

  test("lists one row per workflow, worst success rate first", async () => {
    renderInsights();
    await screen.findByTestId("completed-insights");

    const rows = screen.getAllByTestId("insights-workflow-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("cheer-finding-verify");
    expect(rows[0]).toHaveTextContent("76.5%");
    expect(rows[1]).toHaveTextContent("cheer-version-analysis");
  });

  test("selecting a workflow puts it in the URL and shows its task timings and failures", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("link", { name: "cheer-finding-verify" }));

    await waitFor(() => expect(history.location.search).toContain("workflow=cheer-finding-verify"));
    await screen.findByTestId("insights-workflow-detail");
    expect(screen.getByRole("heading", { name: /Where the time goes/ })).toBeInTheDocument();
    // The task appears in both panels: as a timing row and as the failing task.
    expect(screen.getAllByText("run-verifier")).toHaveLength(2);
    expect(screen.getByText(/Task exceeded its timeout of 60 minutes/)).toBeInTheDocument();
  });

  test("hides the comparison when the toggle is off", async () => {
    renderInsights(`/${WORKSPACE}/insights?compare=off`);
    await screen.findByTestId("completed-insights");

    expect(screen.getByTestId("insights-tile-runs")).not.toHaveTextContent("vs previous");
  });

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

  test("says so when the period holds no runs", async () => {
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), () =>
        HttpResponse.json({
          totals: { runs: 0, byTrigger: {} },
          previous: { runs: 0, byTrigger: {} },
          daily: [],
          workflows: [],
        }),
      ),
    );
    renderInsights();

    expect(await screen.findByText(/No runs in this period/)).toBeInTheDocument();
  });
});

describe("Insights --- loader", () => {
  test("does not throw when a fetch fails - surfaces per-source error flags instead", async () => {
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), () => HttpResponse.json({}, { status: 500 })),
      http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const data = await loader({ params: { workspace: WORKSPACE }, request: new Request(`http://localhost/${WORKSPACE}/insights`) });

    expect(data.errorLoadingInsights).toBe(true);
    expect(data.errorLoadingWorkflows).toBe(true);
    expect(data.summary).toBeNull();
    expect(data.workflowOptions).toEqual([]);
  });

  test("reads the selected workflow's detail in the same wave as the summary", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), trace.resolver("summary", {})),
      http.get(serviceUrl.workspace.workflow.getWorkflows({ workspace: ":workspace" }), trace.resolver("workflows", { content: [] })),
      http.get(
        serviceUrl.workspace.getInsightsWorkflow({ workspace: ":workspace", workflow: ":workflow" }),
        trace.resolver("detail", {}),
      ),
    );

    await loader({
      params: { workspace: WORKSPACE },
      request: new Request(`http://localhost/${WORKSPACE}/insights?workflow=cheer-finding-verify`),
    });

    expect(trace.startedTogether(3)).toBe(true);
  });

  test("passes the filters and the period through to the summary read", async () => {
    let captured = "";
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), ({ request }) => {
        captured = new URL(request.url).search;
        return HttpResponse.json({});
      }),
    );

    await loader({
      params: { workspace: WORKSPACE },
      request: new Request(
        `http://localhost/${WORKSPACE}/insights?statuses=failed&workflows=a,b&triggers=schedule&fromDate=100&toDate=200`,
      ),
    });

    expect(queryString.parse(captured, queryStringOptions)).toEqual({
      statuses: "failed",
      workflows: ["a", "b"],
      triggers: "schedule",
      fromDate: "100",
      toDate: "200",
    });
  });

  // The default window must be computed per request: this module is imported once into a
  // long-lived Node server under ssr:true (Actions.loader.spec.ts guards the same hazard).
  test("computes the default period per request, not at module load", async () => {
    const captured: Array<string | null> = [];
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), ({ request }) => {
        captured.push(new URL(request.url).searchParams.get("toDate"));
        return HttpResponse.json({});
      }),
    );
    const run = () => loader({ params: { workspace: WORKSPACE }, request: new Request(`http://localhost/${WORKSPACE}/insights`) });

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
});
