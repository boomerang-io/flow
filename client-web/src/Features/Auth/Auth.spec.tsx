import React from "react";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, test, vi } from "vitest";
import { server } from "ApiServer/msw/node";
import { APP_ROOT } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { browserNavigation } from "./authClient";
import AuthCallback from "./AuthCallback";
import AuthLogout from "./AuthLogout";
import SignedOut from "./SignedOut";

/*
 * The sign-in surfaces, per auth mode as served by GET /auth/config. The MSW default for that
 * route is mode "none" (see ApiServer/msw/handlers.ts); each test overrides it with server.use().
 *
 * The OIDC protocol itself is no longer tested here: the dance runs server-side (see
 * oidc.server.ts and Auth.action.node.spec.ts, where the authorize redirect, state check,
 * {idToken, nonce} exchange and Set-Cookie relay are pinned in a Node environment). These specs
 * cover what the browser still owns - the signed-out page's surfaces, the proxy silent exchange,
 * the callback's error/loading rendering, and logout.
 *
 * Harness note: window.location is non-configurable in jsdom, so components navigate through
 * authClient.browserNavigation - specs spy on that seam instead of the real location.
 */

const ISSUER = "https://idp.example/realms/flow";

afterEach(() => {
  vi.restoreAllMocks();
});

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
  test("offers sign-in as a form POST to the server-side sign-in action, carrying the return path", async () => {
    server.use(
      http.get(serviceUrl.getAuthConfig(), () =>
        HttpResponse.json({ mode: "oidc", issuer: ISSUER, clientId: "flow-web" }),
      ),
    );

    global.rtlContextRouterRender(<SignedOut onSignedIn={vi.fn()} />);
    const signIn = await screen.findByRole("button", { name: /sign in/i });

    // The OIDC dance is server-side now: the button submits a real document POST to the sign-in
    // action (which answers with the authorize redirect) instead of building an authorize URL in
    // the browser. reloadDocument keeps it a plain form navigation, so the transient flow
    // cookies and the 302 to the issuer behave as ordinary browser navigation.
    // The form element and its hidden input ARE the wire contract under test, and neither
    // carries an accessible role to query by - hence the two targeted lint exemptions.
    // eslint-disable-next-line testing-library/no-node-access
    const form = signIn.closest("form");
    expect(form).not.toBeNull();
    expect(form).toHaveAttribute("method", "post");
    expect(form?.getAttribute("action")).toMatch(/\/auth\/signin$/);
    // eslint-disable-next-line testing-library/no-node-access
    const returnPath = form?.querySelector<HTMLInputElement>('input[name="returnPath"]');
    expect(returnPath?.value).toBe(window.location.pathname + window.location.search);
  });
});

describe("AuthCallback", () => {
  test("renders the failure surface with a way back - never an automatic retry", () => {
    global.rtlRouterRender(
      <AuthCallback error="The sign-in response did not match this browser's sign-in request (state mismatch)." />,
    );

    expect(screen.getByText("Sign-in didn't complete")).toBeInTheDocument();
    expect(screen.getByRole("alert").textContent).toMatch(/state mismatch/);
    // The recovery affordance is a link back to the app - the 401 page owns restarting sign-in.
    expect(screen.getByRole("link", { name: "Back to sign-in" })).toHaveAttribute("href", `${APP_ROOT}/`);
  });

  test("renders the working shell while the server-side flow completes", () => {
    global.rtlRouterRender(<AuthCallback />);
    expect(screen.getByText("Signing you in...")).toBeInTheDocument();
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
