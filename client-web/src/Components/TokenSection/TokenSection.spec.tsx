import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { TokenActorKind, TokenType } from "Constants";
import TokenSection from "./TokenSection";
import { workflowTokensLoader, tokenAction } from "./tokenRoute";
import { isActionError } from "Utils/actionResult";
import { renderWithContext } from "Utils/testing/render";

// Route-module test pattern (see GlobalTokens.spec.tsx / GlobalParameters.spec.tsx): build the
// same shape the real router config uses - a <Route> carrying loader/action alongside its
// element - so rtlContextRouterRender runs them instead of wrapping the element in its usual
// catch-all. TokenSection has no props-based data path any more: everything it renders comes
// from the loader, which is the point of this conversion.
// Must stay a real match for ROUTE_PATH: an unmatched route renders react-router's default
// "Unexpected Application Error! 404 Not Found" page, which still satisfies a loose findByText
// and would otherwise be baked into a passing test.
const ROUTE_PATH = "/:workspace/editor/:workflow/*";
const ROUTE = "/test-workspace/editor/test-workflow/configure/tokens";

function renderTokenSection() {
  return renderWithContext(
    <Route
      path={ROUTE_PATH}
      loader={workflowTokensLoader}
      action={tokenAction}
      element={<TokenSection type={TokenType.Key} principal="test-workflow" actorKind={TokenActorKind.Workflow} />}
    />,
    { route: ROUTE },
  );
}

describe("TokenSection --- loader-driven render", () => {
  test("renders the tokens resolved by the route loader", async () => {
    renderTokenSection();
    const rows = await screen.findAllByText("test-token-user");
    expect(rows.length).toBeGreaterThan(0);
  });

  test("renders the create-token trigger alongside the table", async () => {
    renderTokenSection();
    expect(await screen.findByTestId("create-token-button")).toBeInTheDocument();
  });

  // The expanded row is what surfaces the server-driven permission grid on an existing token;
  // TableExpandedRow keeps it mounted (aria-hidden) while collapsed, so it is queryable here.
  test("renders each token's permission scope/principal detail", async () => {
    renderTokenSection();
    expect(await screen.findByText("64b8d5a5040e205ee3383ab1")).toBeInTheDocument();
  });

  test("renders an error state without throwing when the token fetch fails", async () => {
    server.use(http.get(serviceUrl.getTokens({ query: "" }), () => HttpResponse.json({}, { status: 500 })));
    renderTokenSection();
    expect(await screen.findByText(/something went wrong/i)).toBeInTheDocument();
  });
});

describe("TokenSection --- loader", () => {
  // Called directly rather than through a render, the way GlobalTokens.spec.tsx exercises its
  // action - this asserts the loader's own contract (the `tokenSection` key every token-rendering
  // route must return, and the catalog that PermissionSelector reads off it).
  test("returns tokens and the permission catalog under the tokenSection key", async () => {
    const result = await workflowTokensLoader({
      params: { workflow: "test-workflow" },
      request: new Request(`http://localhost${ROUTE}`),
    });

    expect(result.tokenSection.errorLoading).toBe(false);
    expect(result.tokenSection.tokens.length).toBeGreaterThan(0);
    expect(result.tokenSection.catalog?.actions).toEqual(["read", "write", "delete", "action"]);
  });

  // The catalog and the token list are fetched with Promise.allSettled precisely so one failing
  // cannot blank out the other.
  test("still returns tokens when the catalog fetch fails", async () => {
    server.use(http.get(serviceUrl.getTokenCatalog({ query: "" }), () => HttpResponse.json({}, { status: 500 })));

    const result = await workflowTokensLoader({
      params: { workflow: "test-workflow" },
      request: new Request(`http://localhost${ROUTE}`),
    });

    expect(result.tokenSection.catalog).toBeNull();
    expect(result.tokenSection.errorLoading).toBe(false);
    expect(result.tokenSection.tokens.length).toBeGreaterThan(0);
  });
});

describe("TokenSection --- action", () => {
  test("deletes a token through the mocked API", async () => {
    const request = new Request(`http://localhost${ROUTE}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete", tokenId: "60e3a0b4e4b0c9b6e0b0b0b0" }),
    });

    const result = await tokenAction({ request });

    expect(result).toEqual({ intent: "delete" });
  });

  test("creates a token through the mocked API", async () => {
    const request = new Request(`http://localhost${ROUTE}`, {
      method: "post",
      body: new URLSearchParams({
        intent: "create",
        body: JSON.stringify({ name: "my-new-token", type: TokenType.Key, principal: "test-workflow" }),
      }),
    });

    // Success is returned as the plain payload, not wrapped in data() - see actionResult.ts.
    const result = (await tokenAction({ request })) as unknown as { intent: string };

    expect(isActionError(result)).toBe(false);
    expect(result.intent).toBe("create");
  });

  test("surfaces a failed delete without throwing", async () => {
    server.use(
      http.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), () => HttpResponse.json({}, { status: 500 })),
    );
    const request = new Request(`http://localhost${ROUTE}`, {
      method: "post",
      body: new URLSearchParams({ intent: "delete", tokenId: "60e3a0b4e4b0c9b6e0b0b0b0" }),
    });

    // Calling `tokenAction` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await tokenAction({ request })) as unknown as { data: { intent: string } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("delete");
  });
});

describe("TokenSection --- action intent guard", () => {
  // /profile composes this action with the profile's own (app/routes/profile.tsx dispatches on
  // `intent`), so an unrecognised intent must NOT fall through to the delete branch - it used to,
  // which would have fired DELETE /token/undefined for a profile submission.
  test("rejects an unrecognised intent instead of deleting", async () => {
    let deleteCalled = false;
    server.use(
      http.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), () => {
        deleteCalled = true;
        return HttpResponse.json({});
      }),
    );

    const result = (await tokenAction({
      request: new Request(`http://localhost${ROUTE}`, {
        method: "post",
        body: new URLSearchParams({ intent: "updateProfile", name: "someone" }),
      }),
    })) as unknown as { data: { intent: string; error: { title: string; message: string } } };

    expect(result.data).toEqual({
      intent: "unknown",
      error: {
        title: "Unsupported Token Action",
        message: 'The token action does not handle the "updateProfile" intent.',
      },
    });
    expect(deleteCalled).toBe(false);
  });

  test("refuses a delete with no tokenId", async () => {
    let deleteCalled = false;
    server.use(
      http.delete(serviceUrl.deleteToken({ tokenId: ":tokenId" }), () => {
        deleteCalled = true;
        return HttpResponse.json({});
      }),
    );

    const result = (await tokenAction({
      request: new Request(`http://localhost${ROUTE}`, {
        method: "post",
        body: new URLSearchParams({ intent: "delete" }),
      }),
    })) as unknown as { data: unknown };

    expect(isActionError(result.data)).toBe(true);
    expect(deleteCalled).toBe(false);
  });
});
