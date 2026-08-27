import React from "react";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, test, vi, type Mock } from "vitest";
import { server } from "ApiServer/msw/node";
import { APP_ROOT } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { AUTH_STASH_KEY, browserNavigation, s256Challenge } from "./authClient";
import AuthCallback from "./AuthCallback";
import AuthLogout from "./AuthLogout";
import SignedOut from "./SignedOut";

/*
 * The sign-in flow (specifications/authentication.md), per auth mode as served by GET
 * /auth/config. The MSW default for that route is mode "none" (see ApiServer/msw/handlers.ts);
 * each test overrides it with server.use() for the mode under test.
 *
 * Two harness notes:
 * - window.location is non-configurable in jsdom, so components navigate through
 *   authClient.browserNavigation - specs spy on that seam instead of the real location.
 * - setupTests.tsx replaces sessionStorage with plain vi.fn() mocks (no real storage), so the
 *   PKCE stash is asserted via setItem's recorded calls and injected via getItem's mock return.
 */

const ISSUER = "https://idp.example/realms/flow";

// The PKCE S256 challenge needs SubtleCrypto. jsdom's own window.crypto lacks it, but vitest's
// jsdom environment keeps Node's webcrypto on the global (verified by probe) - so no polyfill.

beforeEach(() => {
  (sessionStorage.getItem as Mock).mockReset();
  (sessionStorage.setItem as Mock).mockReset();
  (sessionStorage.removeItem as Mock).mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

const oidcStash = {
  state: "expected-state",
  nonce: "expected-nonce",
  verifier: "expected-verifier",
  clientId: "flow-web",
  tokenEndpoint: `${ISSUER}/token`,
  returnPath: `${APP_ROOT}/boomerang/activity`,
};

describe("SignedOut --- mode none", () => {
  test("renders the plain signed-out page with no sign-in surface", async () => {
    let configRequests = 0;
    server.use(
      http.get(serviceUrl.getAuthConfig(), () => {
        configRequests += 1;
        return HttpResponse.json({ mode: "none" });
      }),
    );

    global.rtlContextRouterRender(<SignedOut onSignedIn={vi.fn()} />);

    await waitFor(() => expect(configRequests).toBe(1));
    expect(screen.getByText("You're not signed in")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument();
  });
});

describe("SignedOut --- mode oidc", () => {
  test("the sign-in action redirects to the authorize URL with an S256 challenge, state and nonce", async () => {
    server.use(
      http.get(serviceUrl.getAuthConfig(), () =>
        HttpResponse.json({ mode: "oidc", issuer: ISSUER, clientId: "flow-web" }),
      ),
      http.get(`${ISSUER}/.well-known/openid-configuration`, () =>
        HttpResponse.json({
          authorization_endpoint: `${ISSUER}/authorize`,
          token_endpoint: `${ISSUER}/token`,
        }),
      ),
    );
    const assignSpy = vi.spyOn(browserNavigation, "assign").mockImplementation(() => {});

    global.rtlContextRouterRender(<SignedOut onSignedIn={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(assignSpy).toHaveBeenCalledTimes(1));
    const authorizeUrl = new URL(assignSpy.mock.calls[0][0]);
    expect(`${authorizeUrl.origin}${authorizeUrl.pathname}`).toBe(`${ISSUER}/authorize`);
    expect(authorizeUrl.searchParams.get("response_type")).toBe("code");
    expect(authorizeUrl.searchParams.get("client_id")).toBe("flow-web");
    expect(authorizeUrl.searchParams.get("redirect_uri")).toBe(`${window.location.origin}${APP_ROOT}/auth/callback`);
    expect(authorizeUrl.searchParams.get("code_challenge_method")).toBe("S256");

    const state = authorizeUrl.searchParams.get("state");
    const nonce = authorizeUrl.searchParams.get("nonce");
    const challenge = authorizeUrl.searchParams.get("code_challenge");
    expect(state).toBeTruthy();
    expect(nonce).toBeTruthy();
    expect(challenge).toBeTruthy();

    // The URL's state/nonce and the sessionStorage stash are the same values, and the challenge
    // really is the S256 of the stashed verifier - not some fixed or unrelated string.
    const stashWrite = (sessionStorage.setItem as Mock).mock.calls.find(([key]) => key === AUTH_STASH_KEY);
    expect(stashWrite).toBeTruthy();
    const stash = JSON.parse(stashWrite![1]);
    expect(stash.state).toBe(state);
    expect(stash.nonce).toBe(nonce);
    expect(stash.tokenEndpoint).toBe(`${ISSUER}/token`);
    await expect(s256Challenge(stash.verifier)).resolves.toBe(challenge);
  });
});

describe("AuthCallback", () => {
  test("refuses a callback whose state does not match the stashed sign-in", async () => {
    (sessionStorage.getItem as Mock).mockReturnValue(JSON.stringify(oidcStash));
    let exchangeCalls = 0;
    server.use(
      http.post(serviceUrl.postAuthExchange(), () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    global.rtlRouterRender(<Route path="/auth/callback" element={<AuthCallback />} />, {
      route: "/auth/callback?code=the-code&state=WRONG-state",
    });

    expect(await screen.findByText("Sign-in didn't complete")).toBeInTheDocument();
    expect(screen.getByRole("alert").textContent).toMatch(/state mismatch/);
    // Refused means refused: no token was accepted, nothing navigated, and the one-shot stash
    // is consumed so the response cannot be replayed.
    expect(exchangeCalls).toBe(0);
    expect(replaceSpy).not.toHaveBeenCalled();
    expect(sessionStorage.removeItem).toHaveBeenCalledWith(AUTH_STASH_KEY);
    // The recovery affordance is a link back to the app - never an automatic sign-in retry.
    expect(screen.getByRole("link", { name: "Back to sign-in" })).toHaveAttribute("href", `${APP_ROOT}/`);
  });

  test("happy path: exchanges the code with the verifier, POSTs {idToken, nonce}, redirects to the stashed path", async () => {
    (sessionStorage.getItem as Mock).mockReturnValue(JSON.stringify(oidcStash));
    let tokenRequestBody: string | null = null;
    let exchangeRequestBody: unknown = null;
    server.use(
      http.post(`${ISSUER}/token`, async ({ request }) => {
        tokenRequestBody = await request.text();
        return HttpResponse.json({ id_token: "eyJ.fake-id-token.sig" });
      }),
      http.post(serviceUrl.postAuthExchange(), async ({ request }) => {
        exchangeRequestBody = await request.json();
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    global.rtlRouterRender(<Route path="/auth/callback" element={<AuthCallback />} />, {
      route: `/auth/callback?code=the-code&state=${oidcStash.state}`,
    });

    await waitFor(() => expect(replaceSpy).toHaveBeenCalledWith(oidcStash.returnPath));

    // The issuer exchange is a public-client PKCE exchange: code + verifier + client_id, no secret.
    const tokenParams = new URLSearchParams(tokenRequestBody!);
    expect(tokenParams.get("grant_type")).toBe("authorization_code");
    expect(tokenParams.get("code")).toBe("the-code");
    expect(tokenParams.get("code_verifier")).toBe(oidcStash.verifier);
    expect(tokenParams.get("client_id")).toBe(oidcStash.clientId);
    expect(tokenParams.get("redirect_uri")).toBe(`${window.location.origin}${APP_ROOT}/auth/callback`);

    // Our own exchange gets exactly AuthExchangeRequest.java's field names.
    expect(exchangeRequestBody).toEqual({ idToken: "eyJ.fake-id-token.sig", nonce: oidcStash.nonce });

    // The stash is one-shot.
    expect(sessionStorage.removeItem).toHaveBeenCalledWith(AUTH_STASH_KEY);
  });
});

describe("SignedOut --- mode proxy", () => {
  test("a 401 triggers exactly one silent exchange attempt, not a loop", async () => {
    let exchangeCalls = 0;
    server.use(
      http.get(serviceUrl.getAuthConfig(), () => HttpResponse.json({ mode: "proxy" })),
      http.post(serviceUrl.postAuthExchange(), () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const onSignedIn = vi.fn();

    global.rtlContextRouterRender(<SignedOut onSignedIn={onSignedIn} />);

    await waitFor(() => expect(exchangeCalls).toBe(1));
    // The failed attempt lands on the terminal signed-out page (the transient "Signing you in"
    // heading is gone)...
    await waitFor(() => expect(screen.queryByText("Signing you in")).not.toBeInTheDocument());
    expect(screen.getByText("You're not signed in")).toBeInTheDocument();
    expect(onSignedIn).not.toHaveBeenCalled();
    // ...and stays at exactly one attempt after everything has settled.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(exchangeCalls).toBe(1);
  });

  test("a successful silent exchange re-runs the bootstrap", async () => {
    let exchangeCalls = 0;
    server.use(
      http.get(serviceUrl.getAuthConfig(), () => HttpResponse.json({ mode: "proxy" })),
      http.post(serviceUrl.postAuthExchange(), () => {
        exchangeCalls += 1;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const onSignedIn = vi.fn();

    global.rtlContextRouterRender(<SignedOut onSignedIn={onSignedIn} />);

    await waitFor(() => expect(onSignedIn).toHaveBeenCalledTimes(1));
    expect(exchangeCalls).toBe(1);
  });
});

describe("AuthLogout", () => {
  test("calls the logout endpoint and hard-navigates to the app root", async () => {
    let logoutCalls = 0;
    server.use(
      http.post(serviceUrl.postAuthLogout(), () => {
        logoutCalls += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    global.rtlRouterRender(<AuthLogout />);

    await waitFor(() => expect(replaceSpy).toHaveBeenCalledWith(`${APP_ROOT}/`));
    expect(logoutCalls).toBe(1);
  });

  test("proxy mode chains logout to the proxy's own sign-out URL", async () => {
    // Revoking the Flow session alone is not an exit in proxy mode: the surviving proxy session
    // signs the caller straight back in on the next 401. The config's signOutUrl is the proxy's
    // logout, and the landing must chain there after the revoke.
    let logoutCalls = 0;
    server.use(
      http.get(serviceUrl.getAuthConfig(), () =>
        HttpResponse.json({ mode: "proxy", signOutUrl: "https://sso.example.com/pkmslogout" }),
      ),
      http.post(serviceUrl.postAuthLogout(), () => {
        logoutCalls += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    global.rtlRouterRender(<AuthLogout />);

    await waitFor(() =>
      expect(replaceSpy).toHaveBeenCalledWith("https://sso.example.com/pkmslogout"),
    );
    expect(logoutCalls).toBe(1);
  });
});
