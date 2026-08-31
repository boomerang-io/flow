// @vitest-environment node
//
// The server-side OIDC sign-in flow (specifications/authentication.md - maintainer ruling
// 2026-08-31: the PKCE dance moved off the browser into route action/loader halves on the SSR
// server, remix-auth v4 + remix-auth-oauth2 v3 underneath). These run in a REAL Node environment
// because that is where the flow now executes: the browser only ever sees the authorize redirect
// and the final Set-Cookie relay - the id_token and code_verifier never leave this process.
//
// The wire assertions preserved at the same strength as the retired browser-side suite:
// - the authorize redirect carries an S256 challenge, state and nonce (and the challenge really
//   is the S256 of the verifier the strategy stashed - not an unrelated string);
// - the callback refuses a mismatched state without touching the exchange;
// - the exchange receives exactly {idToken, nonce} (field names per AuthExchangeRequest.java);
// - the Java exchange's Set-Cookie is relayed verbatim onto the redirect the browser follows.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";

const INTERNAL_ORIGIN = "http://core-service.internal";
const ISSUER = "https://idp.example/realms/flow";
const APP_ORIGIN = "http://localhost:8080";
const SIGNIN_URL = `${APP_ORIGIN}/apps/flow/auth/signin`;
const CALLBACK_URI = `${APP_ORIGIN}/apps/flow/auth/callback`;
const RETURN_PATH = "/apps/flow/boomerang/activity";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

/** GET /auth/config (unauthenticated, settings-backed) + the issuer's OIDC discovery document. */
function useOidcConfig(issuer = ISSUER, clientId = "flow-web") {
  server.use(
    http.get(`${INTERNAL_ORIGIN}/api/auth/config`, () => HttpResponse.json({ mode: "oidc", issuer, clientId })),
    http.get(`${issuer}/.well-known/openid-configuration`, () =>
      HttpResponse.json({
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
      }),
    ),
  );
}

/** The document POST a SignedOut-page <Form method="post" reloadDocument> submission arrives as. */
function signinRequest(returnPath = RETURN_PATH) {
  return new Request(SIGNIN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ returnPath }),
  });
}

async function s256(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  // base64 -> base64url by hand: the pinned @types/node predates the "base64url" encoding name.
  return Buffer.from(digest).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** What the browser sends back: each Set-Cookie's name=value pair, joined as a Cookie header. */
function cookieHeaderFrom(setCookies: string[]): string {
  return setCookies.map((cookie) => cookie.split(";")[0]).join("; ");
}

/** Runs the sign-in leg and hands back everything the callback leg needs. */
async function runSignin(returnPath = RETURN_PATH) {
  const { signinAction } = await import("./oidc.server");
  const response: Response = await signinAction({ request: signinRequest(returnPath), params: {}, context: {} } as any);
  const authorizeUrl = new URL(response.headers.get("location")!);
  return {
    response,
    authorizeUrl,
    state: authorizeUrl.searchParams.get("state")!,
    nonce: authorizeUrl.searchParams.get("nonce")!,
    challenge: authorizeUrl.searchParams.get("code_challenge")!,
    cookieHeader: cookieHeaderFrom(response.headers.getSetCookie()),
  };
}

async function runCallback(search: string, cookieHeader: string) {
  const { callbackLoader } = await import("./oidc.server");
  const request = new Request(`${CALLBACK_URI}${search}`, {
    headers: cookieHeader ? { cookie: cookieHeader } : undefined,
  });
  return callbackLoader({ request, params: {}, context: {} } as any);
}

describe("signin action --- Node SSR", () => {
  it("redirects to the authorize URL with S256 + state + nonce, stashed in httpOnly cookies", async () => {
    useOidcConfig();

    const { response, authorizeUrl, state, nonce, challenge } = await runSignin();

    expect(response.status).toBe(302);
    expect(`${authorizeUrl.origin}${authorizeUrl.pathname}`).toBe(`${ISSUER}/authorize`);
    expect(authorizeUrl.searchParams.get("response_type")).toBe("code");
    expect(authorizeUrl.searchParams.get("client_id")).toBe("flow-web");
    expect(authorizeUrl.searchParams.get("redirect_uri")).toBe(CALLBACK_URI);
    expect(authorizeUrl.searchParams.get("scope")).toContain("openid");
    expect(authorizeUrl.searchParams.get("code_challenge_method")).toBe("S256");
    expect(state).toBeTruthy();
    expect(nonce).toBeTruthy();
    expect(challenge).toBeTruthy();

    // The transient flow cookies are the redirect's only baggage, and they are httpOnly: the
    // strategy's state cookie holds state + PKCE verifier, ours holds nonce + returnPath.
    const setCookies = response.headers.getSetCookie();
    const stateCookie = setCookies.find((cookie) => cookie.startsWith("flow_oauth2"));
    expect(stateCookie).toBeTruthy();
    expect(stateCookie).toMatch(/httponly/i);
    const stateParams = new URLSearchParams(decodeURIComponent(stateCookie!.split(";")[0].split(/=(.*)/)[1]));
    expect(stateParams.get("state")).toBe(state);
    // The challenge really is the S256 of the stashed verifier - not a fixed or unrelated string.
    await expect(s256(stateParams.get(state)!)).resolves.toBe(challenge);

    const stashCookie = setCookies.find((cookie) => cookie.startsWith("flow_auth_signin="));
    expect(stashCookie).toBeTruthy();
    expect(stashCookie).toMatch(/httponly/i);
    const stashParams = new URLSearchParams(decodeURIComponent(stashCookie!.split(";")[0].split(/=(.*)/)[1]));
    expect(stashParams.get("nonce")).toBe(nonce);
    expect(stashParams.get("returnPath")).toBe(RETURN_PATH);
  });

  it("reads /auth/config per request - a changed issuer changes the authorize redirect", async () => {
    // The config is settings-backed and may change at runtime; freezing it at module scope is the
    // bug this repo already shipped once (the moment() defaults). Two calls, two issuers.
    useOidcConfig("https://idp-a.example");
    const first = await runSignin();
    expect(first.authorizeUrl.origin).toBe("https://idp-a.example");

    useOidcConfig("https://idp-b.example");
    const second = await runSignin();
    expect(second.authorizeUrl.origin).toBe("https://idp-b.example");
  });

  it("sanitises the returnPath before stashing it - only a same-origin absolute path survives", async () => {
    useOidcConfig();

    for (const hostile of ["https://evil.example/phish", "//evil.example/phish"]) {
      const { response } = await runSignin(hostile);
      const stashCookie = response.headers.getSetCookie().find((cookie) => cookie.startsWith("flow_auth_signin="));
      const stashParams = new URLSearchParams(decodeURIComponent(stashCookie!.split(";")[0].split(/=(.*)/)[1]));
      // The fallback derives from the request's own path (basename-aware), never the submission.
      expect(stashParams.get("returnPath")).toBe("/apps/flow/");
    }
  });
});

describe("callback loader --- Node SSR", () => {
  it("refuses a callback whose state does not match the stashed sign-in", async () => {
    useOidcConfig();
    let exchangeCalls = 0;
    let tokenCalls = 0;
    server.use(
      http.post(`${ISSUER}/token`, () => {
        tokenCalls += 1;
        return HttpResponse.json({ id_token: "eyJ.fake-id-token.sig" });
      }),
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { cookieHeader } = await runSignin();
    const result = await runCallback("?code=the-code&state=WRONG-state", cookieHeader);

    // Refused means refused: readable error data for the route component, no redirect, no token
    // accepted anywhere.
    expect(result).not.toBeInstanceOf(Response);
    expect((result as { error: string }).error).toMatch(/state/i);
    expect(tokenCalls).toBe(0);
    expect(exchangeCalls).toBe(0);
  });

  it("happy path: exchanges the code server-side as a public client, POSTs {idToken, nonce}, relays the Set-Cookie", async () => {
    useOidcConfig();
    const sessionSetCookie = "flow_session=bfs_0000-1111; Path=/; Max-Age=3600; Secure; HttpOnly; SameSite=Lax";
    let tokenRequest: { body: string; authorization: string | null } | null = null;
    let exchangeBody: unknown = null;
    server.use(
      http.post(`${ISSUER}/token`, async ({ request }) => {
        tokenRequest = { body: await request.text(), authorization: request.headers.get("authorization") };
        return HttpResponse.json({ id_token: "eyJ.fake-id-token.sig", access_token: "at", token_type: "Bearer" });
      }),
      http.post(`${INTERNAL_ORIGIN}/api/auth/exchange`, async ({ request }) => {
        exchangeBody = await request.json();
        return new HttpResponse(null, { status: 200, headers: { "Set-Cookie": sessionSetCookie } });
      }),
    );

    const { state, nonce, challenge, cookieHeader } = await runSignin();
    const response = await runCallback(`?code=the-code&state=${state}`, cookieHeader);

    expect(response).toBeInstanceOf(Response);
    expect((response as Response).status).toBe(302);
    expect((response as Response).headers.get("location")).toBe(RETURN_PATH);

    // The issuer exchange is a public-client PKCE exchange: code + verifier + client_id in the
    // body, no client secret and no Basic authorization anywhere.
    const tokenParams = new URLSearchParams(tokenRequest!.body);
    expect(tokenRequest!.authorization).toBeNull();
    expect(tokenParams.get("grant_type")).toBe("authorization_code");
    expect(tokenParams.get("code")).toBe("the-code");
    expect(tokenParams.get("client_id")).toBe("flow-web");
    expect(tokenParams.get("redirect_uri")).toBe(CALLBACK_URI);
    await expect(s256(tokenParams.get("code_verifier")!)).resolves.toBe(challenge);

    // Our own exchange gets exactly AuthExchangeRequest.java's field names, with the nonce the
    // strategy put on the authorize request - the backend exact-matches it against the token.
    expect(exchangeBody).toEqual({ idToken: "eyJ.fake-id-token.sig", nonce });

    // Java stays the session authority: its Set-Cookie is relayed VERBATIM onto the redirect,
    // and the transient flow cookies are expired - this process stores nothing else.
    const setCookies = (response as Response).headers.getSetCookie();
    expect(setCookies).toContain(sessionSetCookie);
    expect(setCookies.find((cookie) => cookie.startsWith("flow_auth_signin="))).toMatch(/expires=thu, 01 jan 1970/i);
    expect(setCookies.find((cookie) => cookie.startsWith("flow_oauth2"))).toMatch(/expires=thu, 01 jan 1970/i);
  });

  it("refuses a callback when no sign-in is in progress in this browser", async () => {
    useOidcConfig();
    let tokenCalls = 0;
    server.use(
      http.post(`${ISSUER}/token`, () => {
        tokenCalls += 1;
        return HttpResponse.json({ id_token: "eyJ.fake-id-token.sig" });
      }),
    );

    const result = await runCallback("?code=the-code&state=some-state", "");

    expect(result).not.toBeInstanceOf(Response);
    expect((result as { error: string }).error).toMatch(/no sign-in is in progress/i);
    expect(tokenCalls).toBe(0);
  });

  it("surfaces a provider error without starting a token exchange", async () => {
    useOidcConfig();
    let tokenCalls = 0;
    server.use(
      http.post(`${ISSUER}/token`, () => {
        tokenCalls += 1;
        return HttpResponse.json({ id_token: "eyJ.fake-id-token.sig" });
      }),
    );

    const { cookieHeader } = await runSignin();
    const result = await runCallback("?error=access_denied", cookieHeader);

    expect(result).not.toBeInstanceOf(Response);
    expect((result as { error: string }).error).toMatch(/access_denied/);
    expect(tokenCalls).toBe(0);
  });
});
