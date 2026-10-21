import { vi } from "vitest";
import { http, HttpResponse } from "msw";
import queryString, { StringifyOptions } from "query-string";
import { Route } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as fixtures from "ApiServer/fixtures";
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

const sums = (totalTokens: number, cost: number | null) => ({
  aiTaskRuns: 3,
  promptTokens: totalTokens - 100,
  completionTokens: 100,
  totalTokens,
  reasoningTokens: 0,
  cachedPromptTokens: 0,
  cost,
});

const usage = {
  ...sums(12_400, 0.0312),
  byModel: [
    { model: "openai/gpt-4o-mini", ...sums(12_000, 0.0312) },
    { model: null, ...sums(400, null) },
  ],
};

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
        "?" + queryString.stringify({ workflows: "Personal - Java - Deploy" }, queryStringOptions),
      ),
    );
  });

  test("shows no model usage panel when the period ran no ai task", async () => {
    renderInsights();
    await screen.findByTestId("completed-insights");

    expect(screen.queryByTestId("insights-usage")).not.toBeInTheDocument();
  });

  test("shows model usage, with cost and one row per model, when the period ran ai tasks", async () => {
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), () =>
        HttpResponse.json({ ...fixtures.insights, usage }),
      ),
    );
    renderInsights();

    const panel = await screen.findByTestId("insights-usage");
    expect(panel).toHaveTextContent("Total tokens12,400");
    expect(panel).toHaveTextContent("Cost0.0312");
    expect(panel).toHaveTextContent("openai/gpt-4o-mini");
    // A model the endpoint did not name is still its own row.
    expect(panel).toHaveTextContent("Not reported");
  });

  test("the selected workflow's ai tasks show their tokens and cost", async () => {
    server.use(
      http.get(serviceUrl.workspace.getInsightsWorkflow({ workspace: ":workspace", workflow: ":workflow" }), () =>
        HttpResponse.json({
          ...fixtures.insightsWorkflow,
          tasks: [
            { ...fixtures.insightsWorkflow.tasks[0], totalTokens: null, cost: null },
            { ...fixtures.insightsWorkflow.tasks[1], totalTokens: 12_000, cost: 0.0312 },
          ],
          usage,
        }),
      ),
    );
    renderInsights(`/${WORKSPACE}/insights?workflow=cheer-finding-verify`);

    await screen.findByTestId("insights-detail-usage");
    expect(screen.getByTestId("insights-task-usage-run-verifier")).toHaveTextContent("12,000 tokens · 0.0312");
    expect(screen.getByTestId("insights-task-usage-fetch-findings")).toBeEmptyDOMElement();
  });

  test("filtering by label updates the URL search params on Enter", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.type(screen.getByLabelText("Filter by label"), "archie.analysisType=knowledge{enter}");

    await waitFor(() =>
      expect(history.location.search).toBe(
        "?" + queryString.stringify({ labels: "archie.analysisType=knowledge" }, queryStringOptions),
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

  test("passes the label filter to both the summary and the selected workflow's detail", async () => {
    const captured: Record<string, string> = {};
    server.use(
      http.get(serviceUrl.workspace.getInsights({ workspace: ":workspace" }), ({ request }) => {
        captured.summary = new URL(request.url).search;
        return HttpResponse.json({});
      }),
      http.get(
        serviceUrl.workspace.getInsightsWorkflow({ workspace: ":workspace", workflow: ":workflow" }),
        ({ request }) => {
          captured.detail = new URL(request.url).search;
          return HttpResponse.json({});
        },
      ),
    );

    await loader({
      params: { workspace: WORKSPACE },
      request: new Request(
        `http://localhost/${WORKSPACE}/insights?workflow=w&labels=archie.analysisType%3Dknowledge,team%3Dai`,
      ),
    });

    const expected = ["archie.analysisType=knowledge", "team=ai"];
    expect(queryString.parse(captured.summary, queryStringOptions).labels).toEqual(expected);
    expect(queryString.parse(captured.detail, queryStringOptions).labels).toEqual(expected);
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

describe("Insights --- task runs", () => {
  test("reads no task runs until a grouping is chosen", async () => {
    let read = false;
    server.use(
      http.get(serviceUrl.workspace.getInsightsTasks({ workspace: ":workspace" }), () => {
        read = true;
        return HttpResponse.json(fixtures.insightsTasks);
      }),
    );
    renderInsights();
    await screen.findByTestId("completed-insights");

    expect(screen.getByText("Choose how to group the period's task runs.")).toBeInTheDocument();
    expect(read).toBe(false);
  });

  test("groups as the URL asks and lists the slowest task runs with a link to their run", async () => {
    const requested: Array<URL> = [];
    server.use(
      http.get(serviceUrl.workspace.getInsightsTasks({ workspace: ":workspace" }), ({ request }) => {
        requested.push(new URL(request.url));
        return HttpResponse.json(fixtures.insightsTasks);
      }),
    );
    renderInsights(`/${WORKSPACE}/insights?taskGroupBy=label:boomerang.io/model&taskBucket=day&labels=team=a`);

    const rows = await screen.findAllByTestId("insights-task-group");
    expect(requested).toHaveLength(1);
    expect(requested[0].searchParams.get("groupBy")).toBe("label:boomerang.io/model");
    expect(requested[0].searchParams.get("bucket")).toBe("day");
    expect(requested[0].searchParams.get("slowest")).toBe("10");
    expect(requested[0].searchParams.get("labels")).toBe("team=a");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("2019-12-30");
    expect(rows[0]).toHaveTextContent("openai/gpt-4o-mini");
    expect(rows[0]).toHaveTextContent("42");
    // The ai task runs' usage: tokens and cost.
    expect(rows[0]).toHaveTextContent("96,600");
    expect(rows[0]).toHaveTextContent("$0.0315");
    expect(screen.getByRole("combobox", { name: /Group by/i })).toHaveTextContent("Model");
    // A task run with no value for the grouping is its own row.
    expect(rows[1]).toHaveTextContent("(none)");
    const slowest = screen.getByTestId("insights-slowest-tasks");
    expect(slowest).toHaveTextContent("summarise");
    expect(within(slowest).getByRole("link")).toHaveAttribute(
      "href",
      expect.stringContaining("5d9e2ea2a1a6b10001e37d11"),
    );
  });

  test("choosing a grouping puts it in the URL", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("combobox", { name: /Group by/i }));
    userEvent.click(await screen.findByRole("option", { name: "Task" }));

    await waitFor(() =>
      expect(history.location.search).toBe("?" + queryString.stringify({ taskGroupBy: "task" }, queryStringOptions)),
    );
  });

  test("the model grouping is the server's model label", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("combobox", { name: /Group by/i }));
    userEvent.click(await screen.findByRole("option", { name: "Model" }));

    await waitFor(() =>
      expect(history.location.search).toBe(
        "?" + queryString.stringify({ taskGroupBy: "label:boomerang.io/model" }, queryStringOptions),
      ),
    );
  });

  test("a label grouping waits for the label's key", async () => {
    const { history } = renderInsights();
    await screen.findByTestId("completed-insights");

    userEvent.click(screen.getByRole("combobox", { name: /Group by/i }));
    userEvent.click(await screen.findByRole("option", { name: "Label" }));
    expect(history.location.search).toBe("");
    userEvent.type(await screen.findByLabelText("Label key"), "team{enter}");

    await waitFor(() =>
      expect(history.location.search).toBe("?" + queryString.stringify({ taskGroupBy: "label:team" }, queryStringOptions)),
    );
  });
});
