// @vitest-environment node
//
// The session-side SSR actions (specifications/authentication.md - BFF slice, 2026-09-01): the
// browser no longer calls POST /api/v2/auth/exchange or POST /api/v2/auth/logout itself. Both
// moved into route actions on the SSR server (session.server.ts), because both hinge on the same
// mechanic the sign-in flow already proved (oidc.server.ts): the Java response's Set-Cookie
// header(s) must be relayed VERBATIM onto the action's own response so the httpOnly session
// cookie reaches (or leaves) the browser.
//
// The wire assertions, preserved at the same strength as the retired browser-side calls:
// - the proxy exchange forwards the inbound request's identity headers - cookie,
//   x-forwarded-email, x-forwarded-user, authorization (AuthenticationFilter.java reads exactly
//   these to resolve the proxy-asserted principal; serverFetch alone forwards only the cookie);
// - the exchange's Set-Cookie is relayed verbatim; a failed exchange relays nothing and returns
//   a readable { ok: false } for the single-attempt SignedOut page - never a throw, never a loop;
// - /auth/signin's route action dispatches on intent: "proxy-exchange" runs the exchange and
//   NEVER starts the OIDC dance; a plain returnPath submission starts OIDC and never exchanges;
// - logout POSTs the Java logout with the inbound session cookie, relays the clearing
//   Set-Cookie, and returns the proxy's own signOutUrl read from /auth/config server-side (the
//   proxy-chain: revoking the Flow session alone is not an exit - the surviving proxy session
//   signs the caller straight back in on the next 401);
// - a dead session endpoint still resolves logout (the user is signed out either way).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";

const INTERNAL_ORIGIN = "http://core-service.internal";
const APP_ORIGIN = "http://localhost:3000";
const ISSUER = "https://idp.example/realms/flow";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

function actionArgs(request: Request) {
  return { request, params: {}, context: {} } as any;
}

/** Reads the Set-Cookie list off a data() result's ResponseInit (empty when there is none). */
function setCookiesOf(result: any): string[] {
  return result?.init?.headers ? new Headers(result.init.headers).getSetCookie() : [];
}

function exchangeRequest(headers: Record<string, string> = {}) {
  return new Request(`${APP_ORIGIN}/apps/flow/auth/signin.data`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", ...headers },
    body: new URLSearchParams({ intent: "proxy-exchange" }),
  });
}

describe("proxy exchange action --- Node SSR", () => {
  it("forwards the proxy identity headers and relays the session Set-Cookie verbatim", async () => {
    const sessionSetCookie = "flow_session=bfs_0000-1111; Path=/; Max-Age=3600; Secure; HttpOnly; SameSite=Lax";
    let captured: Record<string, string | null> | null = null;
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, ({ request }) => {
        captured = {
          cookie: request.headers.get("cookie"),
          xForwardedEmail: request.headers.get("x-forwarded-email"),
          xForwardedUser: request.headers.get("x-forwarded-user"),
          authorization: request.headers.get("authorization"),
        };
        return new HttpResponse(null, { status: 201, headers: { "Set-Cookie": sessionSetCookie } });
      }),
    );

    const { proxyExchangeAction } = await import("./session.server");
    const result: any = await proxyExchangeAction(
      actionArgs(
        exchangeRequest({
          cookie: "unrelated=1",
          "x-forwarded-email": "jane@corp.example",
          "x-forwarded-user": "jane",
          authorization: "Bearer proxy-forwarded-jwt",
        }),
      ),
    );

    // The proxy asserts identity on the inbound request via these headers (AuthenticationFilter:
    // x-forwarded-email/x-forwarded-user, or a forwarded JWT in Authorization); the SSR action
    // must pass all of them through or the Java exchange has no principal to mint from.
    expect(captured).toEqual({
      cookie: "unrelated=1",
      xForwardedEmail: "jane@corp.example",
      xForwardedUser: "jane",
      authorization: "Bearer proxy-forwarded-jwt",
    });
    expect(result.data).toEqual({ ok: true });
    expect(setCookiesOf(result)).toEqual([sessionSetCookie]);
  });

  it("a refused exchange returns { ok: false } with no cookie - readable data, never a throw", async () => {
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, () => new HttpResponse(null, { status: 401 })),
    );

    const { proxyExchangeAction } = await import("./session.server");
    const result: any = await proxyExchangeAction(actionArgs(exchangeRequest()));

    expect(result.data).toEqual({ ok: false });
    expect(setCookiesOf(result)).toEqual([]);
  });
});

describe("signin route action dispatch --- Node SSR", () => {
  it("intent=proxy-exchange runs the exchange and never starts the OIDC dance", async () => {
    let configCalls = 0;
    let exchangeCalls = 0;
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/auth/config`, () => {
        configCalls += 1;
        return HttpResponse.json({ mode: "proxy" });
      }),
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { signinRouteAction } = await import("./session.server");
    const result: any = await signinRouteAction(actionArgs(exchangeRequest()));

    expect(exchangeCalls).toBe(1);
    // The OIDC start reads /auth/config (buildStrategy) - the exchange path never does.
    expect(configCalls).toBe(0);
    expect(result.data).toEqual({ ok: true });
  });

  it("a plain returnPath submission starts OIDC exactly as before and never exchanges", async () => {
    let exchangeCalls = 0;
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/auth/config`, () =>
        HttpResponse.json({ mode: "oidc", issuer: ISSUER, clientId: "flow-web" }),
      ),
      http.get(`${ISSUER}/.well-known/openid-configuration`, () =>
        HttpResponse.json({
          authorization_endpoint: `${ISSUER}/authorize`,
          token_endpoint: `${ISSUER}/token`,
        }),
      ),
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { signinRouteAction } = await import("./session.server");
    const request = new Request(`${APP_ORIGIN}/apps/flow/auth/signin`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ returnPath: "/apps/flow/boomerang/activity" }),
    });
    const response: any = await signinRouteAction(actionArgs(request));

    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(302);
    expect(new URL(response.headers.get("location")!).pathname).toBe("/realms/flow/authorize");
    expect(exchangeCalls).toBe(0);
  });
});

describe("logout action --- Node SSR", () => {
  it("revokes with the inbound cookie, relays the clearing Set-Cookie, returns the proxy signOutUrl", async () => {
    const clearingSetCookie =
      "flow_session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax";
    let capturedCookie: string | null = null;
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/auth/logout`, ({ request }) => {
        capturedCookie = request.headers.get("cookie");
        return new HttpResponse(null, { status: 204, headers: { "Set-Cookie": clearingSetCookie } });
      }),
      http.get(`${INTERNAL_ORIGIN}/api/auth/config`, () =>
        HttpResponse.json({ mode: "proxy", signOutUrl: "https://sso.example.com/pkmslogout" }),
      ),
    );

    const { logoutAction } = await import("./session.server");
    const request = new Request(`${APP_ORIGIN}/apps/flow/auth/logout.data`, {
      method: "POST",
      headers: { cookie: "flow_session=bfs_0000-1111" },
    });
    const result: any = await logoutAction(actionArgs(request));

    expect(capturedCookie).toBe("flow_session=bfs_0000-1111");
    expect(setCookiesOf(result)).toEqual([clearingSetCookie]);
    // The proxy-chain contract: the component hard-navigates here AFTER the revoke - otherwise
    // the surviving proxy session signs the caller straight back in on the next 401.
    expect(result.data).toEqual({ redirectTo: "https://sso.example.com/pkmslogout" });
  });

  it("still resolves when the logout endpoint is dead - the user is signed out either way", async () => {
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/auth/logout`, () => new HttpResponse(null, { status: 500 })),
      http.get(`${INTERNAL_ORIGIN}/api/auth/config`, () =>
        HttpResponse.json({ mode: "oidc", issuer: ISSUER, clientId: "flow-web" }),
      ),
    );

    const { logoutAction } = await import("./session.server");
    const result: any = await logoutAction(
      actionArgs(new Request(`${APP_ORIGIN}/apps/flow/auth/logout.data`, { method: "POST" })),
    );

    // No signOutUrl in oidc mode - the component falls back to the app root.
    expect(result.data).toEqual({ redirectTo: null });
    expect(setCookiesOf(result)).toEqual([]);
  });
});
