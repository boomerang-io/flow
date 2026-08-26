import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import GlobalTokens, { action, loader } from "./GlobalTokens";

// Route-module test pattern (see GlobalParameters.spec.tsx): build the same shape the real
// router config uses (a <Route> carrying loader/action alongside its element) so
// rtlContextRouterRender runs them instead of wrapping the element in its usual catch-all.
function renderGlobalTokens() {
  return global.rtlContextRouterRender(<Route path="*" loader={loader} action={action} element={<GlobalTokens />} />);
}

describe("GlobalTokens --- loader", () => {
  test("renders tokens resolved by the loader", async () => {
    renderGlobalTokens();
    const rows = await screen.findAllByText("test-token-user");
    expect(rows.length).toBeGreaterThan(0);
  });

  test("renders an error state without throwing when the fetch fails", async () => {
    server.use(http.get(serviceUrl.getTokens({ query: "" }), () => HttpResponse.json({}, { status: 500 })));
    renderGlobalTokens();
    expect(await screen.findByText(/something went wrong/i)).toBeInTheDocument();
  });
});

describe("GlobalTokens --- action", () => {
  // Exercises the action function directly against the mocked API (as `<Route action={action}>`
  // does on submit) rather than click-driving Carbon's ConfirmModal in jsdom - see
  // GlobalParameters.spec.tsx for the same call.
  test("deletes a token through the mocked API", async () => {
    const request = new Request("http://localhost/admin/tokens", {
      method: "post",
      body: new URLSearchParams({ intent: "delete", tokenId: "60e3a0b4e4b0c9b6e0b0b0b0" }),
    });

    const result = await action({ request });

    expect(result).toEqual({ ok: true, intent: "delete" });
  });

  test("surfaces a failed delete without throwing", async () => {
    server.use(http.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), () => HttpResponse.json({}, { status: 500 })));
    const request = new Request("http://localhost/admin/tokens", {
      method: "post",
      body: new URLSearchParams({ intent: "delete", tokenId: "60e3a0b4e4b0c9b6e0b0b0b0" }),
    });

    const result = await action({ request });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("delete");
  });
});
