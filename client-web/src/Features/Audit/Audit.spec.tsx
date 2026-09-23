import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "ApiServer/msw/node";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { renderWithContext } from "Utils/testing/render";
import { AppPath, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import Audit, { loader } from "./Audit";

// The frozen clock (setupTests.tsx) means lodash's debounce timer never fires on its own, so the
// actor Search would never push its value into the URL - the same mock Workspaces.spec.tsx uses.
vi.mock("lodash/debounce", () => ({ default: (fn: (...args: unknown[]) => void) => fn }));

const EMPTY_PAGE = {
  content: [],
  number: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
  numberOfElements: 0,
  empty: true,
};

// Route-module test pattern (see GlobalTokens.spec.tsx): the <Route> carries the loader
// alongside its element so renderWithContext runs it.
function renderAudit(route: string = appLink.audit()) {
  return renderWithContext(<Route path={AppPath.Audit} loader={loader} element={<Audit />} />, { route });
}

describe("Audit --- loader", () => {
  test("renders the events resolved by the loader", async () => {
    renderAudit();
    expect(await screen.findByText("Nightly Sync")).toBeInTheDocument();
    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    expect(screen.getByText("Nightly Robot")).toBeInTheDocument();
  });

  test("shows each outcome as its own tag", async () => {
    renderAudit();
    await screen.findByText("Nightly Sync");
    // Scoped to the table: the stat tiles carry the same three words as labels.
    const table = within(screen.getByRole("table"));
    expect(table.getByText("Success")).toBeInTheDocument();
    expect(table.getByText("Failed")).toBeInTheDocument();
    expect(table.getByText("Denied")).toBeInTheDocument();
  });

  test("renders the stat tiles from the stats endpoint", async () => {
    renderAudit();
    expect(await screen.findByText("Capture level")).toBeInTheDocument();
    expect(screen.getByText("365 days")).toBeInTheDocument();
  });

  test("renders an error state without throwing when the listing fetch fails", async () => {
    server.use(http.get(serviceUrl.getAuditEvents({ query: "" }), () => HttpResponse.json({}, { status: 500 })));
    renderAudit();
    expect(await screen.findByText(/something went wrong/i)).toBeInTheDocument();
  });

  test("warns when capture is off", async () => {
    server.use(
      http.get(serviceUrl.getAuditStats({ query: "" }), () =>
        HttpResponse.json({
          from: "2019-12-02T00:00:00.000+00:00",
          to: null,
          total: 0,
          outcomes: { SUCCESS: 0, FAILED: 0, DENIED: 0 },
          captureEnabled: false,
          level: "WRITE",
          retentionDays: 365,
        }),
      ),
    );
    renderAudit();
    expect(await screen.findByText("Audit capture is off")).toBeInTheDocument();
  });
});

describe("Audit --- filters", () => {
  test("the actor search round-trips through the URL and narrows the table", async () => {
    const { history } = renderAudit();
    await screen.findByText("Nightly Sync");

    userEvent.type(screen.getByPlaceholderText("Filter by actor name or id"), "jane");

    await waitFor(() => expect(history.location.search).toContain("actor=jane"));
    await waitFor(() => expect(screen.queryByText("Nightly Robot")).not.toBeInTheDocument());
    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
  });

  test("an outcome chosen from the dropdown round-trips through the URL", async () => {
    const { history } = renderAudit();
    await screen.findByText("Nightly Sync");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by outcome/i }));
    userEvent.click(screen.getByRole("option", { name: "Denied" }));

    await waitFor(() => expect(history.location.search).toContain("outcome=DENIED"));
  });

  test("a level chosen from the dropdown round-trips through the URL", async () => {
    const { history } = renderAudit();
    await screen.findByText("Nightly Sync");

    userEvent.click(screen.getByRole("combobox", { name: /Filter by level/i }));
    userEvent.click(screen.getByRole("option", { name: "Destructive" }));

    await waitFor(() => expect(history.location.search).toContain("level=DESTRUCTIVE"));
  });

  test("clear filters is offered only when a filter is set, and clears the URL", async () => {
    const { history } = renderAudit(`${appLink.audit()}?outcome=DENIED`);
    await screen.findByText("CI deploy key");

    userEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    await waitFor(() => expect(history.location.search).toBe(""));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Clear filters" })).not.toBeInTheDocument());
  });
});

describe("Audit --- rows and empty states", () => {
  test("a row expands to show its payload as JSON", async () => {
    renderAudit();
    await screen.findByText("Nightly Sync");

    const expanders = screen.getAllByRole("button", { name: /expand current row/i });
    userEvent.click(expanders[0]);

    await waitFor(() => expect(expanders[0]).toHaveAttribute("aria-expanded", "true"));
    expect(screen.getByText(/"sourceIp": "10.12.0.4"/)).toBeInTheDocument();
  });

  test("distinguishes no matching events from no events at all", async () => {
    renderAudit(`${appLink.audit()}?actor=nobody`);
    expect(await screen.findByText("No events match the current filters")).toBeInTheDocument();
  });

  test("says the trail is empty when there are no events and no filters", async () => {
    server.use(http.get(serviceUrl.getAuditEvents({ query: "" }), () => HttpResponse.json(EMPTY_PAGE)));
    renderAudit();
    expect(await screen.findByText("No audit events recorded yet")).toBeInTheDocument();
  });
});
