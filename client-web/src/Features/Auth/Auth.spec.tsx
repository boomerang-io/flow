import React from "react";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import type { ActionFunctionArgs } from "react-router-dom";
import { afterEach, describe, expect, test, vi } from "vitest";
import { server } from "ApiServer/msw/node";
import { APP_ROOT } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { browserNavigation } from "./authClient";
import { renderWithContext, renderWithRouter } from "Utils/testing/render";
import AuthCallback from "./AuthCallback";
import AuthLogout from "./AuthLogout";
import SignedOut from "./SignedOut";

/*
 * The sign-in surfaces, per auth mode. Since the BFF slice (2026-09-01) the browser makes NO
 * /api auth calls at all: the root loader fetches GET /auth/config server-side and hands it to
 * SignedOut as a prop, the proxy silent exchange is a fetcher submission to the /auth/signin
 * route action, and logout is a fetcher submission to the /auth/logout route action - so these
 * specs stub route ACTIONS (the real actions are pinned in Session.action.node.spec.ts, where
 * the header forwarding and Set-Cookie relays run in a real Node environment) and use MSW
 * counters only to prove the browser-side calls are GONE.
 *
 * Harness note: window.location is non-configurable in jsdom, so components navigate through
 * authClient.browserNavigation - specs spy on that seam instead of the real location.
 */

afterEach(() => {
  vi.restoreAllMocks();
});

describe("SignedOut --- mode none", () => {
  test("renders the plain signed-out page from the config prop - no browser config fetch", async () => {
    let configRequests = 0;
    server.use(
      http.get(serviceUrl.getAuthConfig(), () => {
        configRequests += 1;
        return HttpResponse.json({ mode: "none" });
      }),
    );

    renderWithContext(<SignedOut config={{ mode: "none" }} onReloadConfig={vi.fn()} />);

    expect(screen.getByText("You're not signed in")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument();
    // The config arrived through the root loader; the browser-side GET /auth/config is gone.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(configRequests).toBe(0);
  });
});

describe("SignedOut --- mode oidc", () => {
  test("server-renderable: the very first render offers sign-in - no effect, no fetch", () => {
    renderWithContext(
      <SignedOut config={{ mode: "oidc", issuer: "https://idp.example/realms/flow", clientId: "flow-web" }} onReloadConfig={vi.fn()} />,
      { route: "/boomerang/activity?phase=succeeded" },
    );

    // getByRole, not findByRole: the button must be in the INITIAL render output (what SSR
    // serialises - view-source shows the Sign in button), not something an effect adds later.
    const signIn = screen.getByRole("button", { name: /sign in/i });

    // The OIDC dance is server-side: the button submits a real document POST to the sign-in
    // action (which answers with the authorize redirect). reloadDocument keeps it a plain form
    // navigation, so the transient flow cookies and the 302 to the issuer behave as ordinary
    // browser navigation. The form element and its hidden input ARE the wire contract under
    // test, and neither carries an accessible role to query by - hence the lint exemptions.
    // eslint-disable-next-line testing-library/no-node-access
    const form = signIn.closest("form");
    expect(form).not.toBeNull();
    expect(form).toHaveAttribute("method", "post");
    expect(form?.getAttribute("action")).toMatch(/\/auth\/signin$/);
    // The return path derives from the router location (useHref - basename-aware and SSR-safe),
    // not window.location, which the server does not have.
    // eslint-disable-next-line testing-library/no-node-access
    const returnPath = form?.querySelector<HTMLInputElement>('input[name="returnPath"]');
    expect(returnPath?.value).toBe("/boomerang/activity?phase=succeeded");
  });
});

describe("AuthCallback", () => {
  test("renders the failure surface with a way back - never an automatic retry", () => {
    renderWithRouter(
      <AuthCallback error="The sign-in response did not match this browser's sign-in request (state mismatch)." />,
    );

    expect(screen.getByText("Sign-in didn't complete")).toBeInTheDocument();
    expect(screen.getByRole("alert").textContent).toMatch(/state mismatch/);
    // The recovery affordance is a link back to the app - the 401 page owns restarting sign-in.
    expect(screen.getByRole("link", { name: "Back to sign-in" })).toHaveAttribute("href", `${APP_ROOT}/`);
  });

  test("renders the working shell while the server-side flow completes", () => {
    renderWithRouter(<AuthCallback />);
    expect(screen.getByText("Signing you in...")).toBeInTheDocument();
  });
});

describe("SignedOut --- mode proxy", () => {
  test("a 401 triggers exactly one exchange submission to the signin action, not a loop", async () => {
    let actionCalls = 0;
    let submittedIntent: FormDataEntryValue | null = null;
    let browserExchangeCalls = 0;
    server.use(
      http.post(serviceUrl.postAuthExchange(), () => {
        browserExchangeCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const exchangeAction = async ({ request }: ActionFunctionArgs) => {
      actionCalls += 1;
      submittedIntent = (await request.formData()).get("intent");
      return { ok: false };
    };

    renderWithContext(
      <>
        <Route path="/" element={<SignedOut config={{ mode: "proxy" }} onReloadConfig={vi.fn()} />} />
        <Route path="/auth/signin" action={exchangeAction} />
      </>,
    );

    await waitFor(() => expect(actionCalls).toBe(1));
    // The submission carries the intent the /auth/signin route action dispatches on (the real
    // dispatch is pinned in Session.action.node.spec.ts).
    expect(submittedIntent).toBe("proxy-exchange");
    // The failed attempt lands on the terminal signed-out page (the transient "Signing you in"
    // heading is gone)...
    await waitFor(() => expect(screen.queryByText("Signing you in")).not.toBeInTheDocument());
    expect(screen.getByText("You're not signed in")).toBeInTheDocument();
    // ...and stays at exactly one attempt after everything has settled - including the root
    // revalidation react-router runs after every completed action.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(actionCalls).toBe(1);
    // The browser-side POST /auth/exchange is gone.
    expect(browserExchangeCalls).toBe(0);
  });

  test("a successful exchange keeps the working shell up while revalidation takes over", async () => {
    let actionCalls = 0;
    const exchangeAction = async () => {
      actionCalls += 1;
      return { ok: true };
    };

    renderWithContext(
      <>
        <Route path="/" element={<SignedOut config={{ mode: "proxy" }} onReloadConfig={vi.fn()} />} />
        <Route path="/auth/signin" action={exchangeAction} />
      </>,
    );

    await waitFor(() => expect(actionCalls).toBe(1));
    // On success the action's relayed Set-Cookie is already in the browser and react-router's
    // post-action revalidation re-runs the root bootstrap, which unmounts this page. Until then
    // the working shell stays up - never a flash of the terminal signed-out page.
    expect(await screen.findByText("Signing you in")).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(actionCalls).toBe(1);
  });
});

describe("SignedOut --- config unavailable", () => {
  test("renders the readable failure; retry re-runs the root loader, not a browser fetch", () => {
    const onReloadConfig = vi.fn();

    renderWithContext(<SignedOut config={null} onReloadConfig={onReloadConfig} />);

    expect(screen.getByText("The sign-in configuration could not be loaded.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onReloadConfig).toHaveBeenCalledTimes(1);
  });
});

describe("AuthLogout", () => {
  test("submits the logout action once and hard-navigates to the app root", async () => {
    let actionCalls = 0;
    let browserLogoutCalls = 0;
    server.use(
      http.post(serviceUrl.postAuthLogout(), () => {
        browserLogoutCalls += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const logoutAction = async () => {
      actionCalls += 1;
      return { redirectTo: null };
    };
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    renderWithRouter(<Route path="/auth/logout" element={<AuthLogout />} action={logoutAction} />, {
      route: "/auth/logout",
    });

    await waitFor(() => expect(replaceSpy).toHaveBeenCalledWith(`${APP_ROOT}/`));
    expect(actionCalls).toBe(1);
    // The browser-side POST /auth/logout is gone - the action owns the revoke + Set-Cookie relay
    // (pinned in Session.action.node.spec.ts).
    expect(browserLogoutCalls).toBe(0);
  });

  test("proxy mode chains logout to the signOutUrl the action read from config server-side", async () => {
    // Revoking the Flow session alone is not an exit in proxy mode: the surviving proxy session
    // signs the caller straight back in on the next 401. The action returns the proxy's own
    // sign-out URL (from /auth/config, read server-side) and the landing chains there.
    const logoutAction = async () => ({ redirectTo: "https://sso.example.com/pkmslogout" });
    const replaceSpy = vi.spyOn(browserNavigation, "replace").mockImplementation(() => {});

    renderWithRouter(<Route path="/auth/logout" element={<AuthLogout />} action={logoutAction} />, {
      route: "/auth/logout",
    });

    await waitFor(() => expect(replaceSpy).toHaveBeenCalledWith("https://sso.example.com/pkmslogout"));
  });
});
