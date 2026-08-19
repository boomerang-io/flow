import { Response } from "miragejs";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { startApiServer } from "ApiServer";
import { serviceUrl } from "Config/servicesConfig";
import GlobalParameters, { action, loader } from "./GlobalParameters";

// Route-module test pattern: build the same shape the real router config uses in
// AppRoutes.tsx (a <Route> carrying loader/action alongside its element) and hand it to
// rtlContextRouterRender - the helper detects a <Route> element and uses it as-is instead of
// wrapping it in its usual catch-all, so the loader/action actually run.
function renderGlobalParameters() {
  return global.rtlContextRouterRender(<Route path="*" loader={loader} action={action} element={<GlobalParameters />} />);
}

let server: any;

beforeEach(() => {
  server = startApiServer();
});

afterEach(() => {
  server.shutdown();
});

describe("GlobalParameters --- loader", () => {
  test("renders parameters resolved by the loader", async () => {
    renderGlobalParameters();
    expect(await screen.findByText("test global label")).toBeInTheDocument();
    expect(screen.getByText("test global password")).toBeInTheDocument();
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

    expect(result).toEqual({ ok: true, intent: "delete", label: "test global label" });
  });

  test("surfaces a failed create/update without throwing", async () => {
    const request = new Request("http://localhost/admin/parameters", {
      method: "post",
      body: new URLSearchParams({
        intent: "create",
        parameter: JSON.stringify({ name: "", label: "Missing name" }),
      }),
    });
    server.post(serviceUrl.getGlobalParameters(), () => new Response(500, {}, {}));

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("create");
  });
});
