import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";
import GlobalParameters, { action, loader } from "./GlobalParameters";

// Route-module test pattern: build the same shape the real router config uses in
// AppRoutes.tsx (a <Route> carrying loader/action alongside its element) and hand it to
// renderWithContext - it detects a <Route> element and uses it as-is instead of
// wrapping it in its usual catch-all, so the loader/action actually run.
function renderGlobalParameters() {
  return renderWithContext(<Route path="*" loader={loader} action={action} element={<GlobalParameters />} />);
}

describe("GlobalParameters --- loader", () => {
  test("renders parameters resolved by the loader", async () => {
    renderGlobalParameters();
    expect(await screen.findByText("test global label")).toBeInTheDocument();
    expect(screen.getByText("test global password")).toBeInTheDocument();
    // A password-typed parameter is tagged Secured and its value masked.
    expect(screen.getByText("Secured")).toBeInTheDocument();
    expect(screen.getByText("******")).toBeInTheDocument();
  });
});

describe("GlobalParameters --- action", () => {
  // Driving this through a real click-through of Carbon's nested overflow-menu -> confirm-modal
  // flow fights the library's own aria-hidden/portal handling in jsdom rather than testing this
  // route's action - so this exercises the action function itself (as `<Route action={action}>`
  // does on submit) against the same mock server the loader test above uses, which is enough to
  // prove the write goes through and the response shape the component expects comes back.
  test("deletes a parameter through the mocked API", async () => {
    const request = new Request("http://localhost/admin/parameters", {
      method: "post",
      body: new URLSearchParams({ intent: "delete", name: "test-global-key", label: "test global label" }),
    });

    const result = await action({ request });

    expect(result).toEqual({ intent: "delete", label: "test global label" });
  });

  test("updates a parameter by a merge patch addressed by name", async () => {
    let sent: { url: string; method: string; contentType: string | null; body: unknown } | undefined;
    server.use(
      http.patch(serviceUrl.getGlobalParameter({ name: ":name" }), async ({ request }) => {
        sent = {
          url: new URL(request.url).pathname,
          method: request.method,
          contentType: request.headers.get("content-type"),
          body: await request.json(),
        };
        return HttpResponse.json({ name: "test-global-key", label: "Renamed" });
      }),
    );
    const request = new Request("http://localhost/admin/parameters", {
      method: "post",
      body: new URLSearchParams({
        intent: "update",
        name: "test-global-key",
        label: "Renamed",
        parameter: JSON.stringify({ label: "Renamed" }),
      }),
    });

    const result = await action({ request });

    expect(result).toEqual({ intent: "update", label: "Renamed" });
    expect(sent?.method).toBe("PATCH");
    expect(sent?.url).toMatch(/\/parameters\/test-global-key$/);
    expect(sent?.contentType).toContain("application/merge-patch+json");
    expect(sent?.body).toEqual({ label: "Renamed" });
  });

  test("surfaces a failed create/update without throwing", async () => {
    const request = new Request("http://localhost/admin/parameters", {
      method: "post",
      body: new URLSearchParams({
        intent: "create",
        parameter: JSON.stringify({ name: "", label: "Missing name" }),
      }),
    });
    server.use(http.post(serviceUrl.getGlobalParameters(), () => HttpResponse.json({}, { status: 500 })));

    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({ request })) as unknown as { data: { intent: string } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("create");
  });
});
