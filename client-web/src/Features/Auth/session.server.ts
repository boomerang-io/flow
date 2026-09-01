/*
 * Session lifecycle actions (specifications/authentication.md - BFF slice, 2026-09-01): the
 * browser no longer POSTs /api/v2/auth/exchange or /api/v2/auth/logout itself. Both were
 * browser-side precisely because their responses' Set-Cookie must reach the browser - and the
 * server-side sign-in flow (oidc.server.ts) already proved the alternative mechanic: run the
 * Java call in a route action via serverFetch(request) (forwarding the inbound cookie) and relay
 * the response's Set-Cookie header(s) VERBATIM onto the action's own response. data()'s
 * ResponseInit headers ride the action's HTTP response, so the httpOnly cookie lands in (or
 * leaves) the browser exactly as before.
 *
 * The `.server.ts` suffix keeps this module out of build/client (route-module splitting) - only
 * the app/routes/auth* route files import it.
 */
import { data, type ActionFunctionArgs } from "react-router";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type { AuthConfig } from "./authClient";
import { signinAction } from "./oidc.server";

/*
 * The headers the authenticating proxy asserts identity with, which the Java side reads to
 * resolve the principal (AuthenticationFilter.java: x-forwarded-email/x-forwarded-user for the
 * proxy identity, Authorization for a proxy-forwarded JWT). serverFetch forwards only the
 * inbound Cookie header - the proxy case authenticates the REQUEST, not a session, so these
 * must travel too or the exchange has no principal to mint from.
 */
const PROXY_IDENTITY_HEADERS = ["authorization", "x-forwarded-user", "x-forwarded-email"] as const;

function proxyIdentityHeaders(request: Request): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const name of PROXY_IDENTITY_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers[name] = value;
  }
  return headers;
}

function relaySetCookies(setCookies: string[]): Headers {
  const headers = new Headers();
  for (const setCookie of setCookies) headers.append("Set-Cookie", setCookie);
  return headers;
}

/*
 * The proxy path: the proxy has already asserted identity on the inbound request via the headers
 * above, so an empty-body POST carrying them is all it takes for Java to mint the session cookie.
 * Success relays that Set-Cookie; react-router's post-action revalidation then re-runs the root
 * bootstrap with the new cookie, which is what signs the app in. Failure is readable data - the
 * caller (SignedOut) decides what it means and MUST NOT retry in a loop.
 */
export async function proxyExchangeAction({ request }: ActionFunctionArgs) {
  try {
    const response = await serverFetch(request).post(serviceUrl.postAuthExchange(), undefined, {
      headers: proxyIdentityHeaders(request),
    });
    return data({ ok: true }, { headers: relaySetCookies(response.headers["set-cookie"] ?? []) });
  } catch {
    return data({ ok: false });
  }
}

/*
 * The /auth/signin route action: one route, two submissions. The SignedOut page's silent proxy
 * exchange is a fetcher submission carrying intent=proxy-exchange; anything else is the Sign in
 * button's document POST starting the OIDC dance (oidc.server.ts). clone() before reading the
 * body - signinAction reads the same formData again.
 */
export async function signinRouteAction(args: ActionFunctionArgs) {
  const formData = await args.request.clone().formData();
  if (formData.get("intent") === "proxy-exchange") {
    return proxyExchangeAction(args);
  }
  return signinAction(args);
}

/*
 * Logout: revoke the session server-side AND clear the cookie (the Java response's Set-Cookie
 * does both - see AuthControllerV2), relayed verbatim. A revoke failure is swallowed on purpose:
 * if the session is already dead, the user is signed out either way. The action also reads
 * GET /auth/config server-side to return where the browser must hard-navigate NEXT: in proxy
 * mode that is the proxy's own signOutUrl (revoking the Flow session alone is not an exit - the
 * surviving proxy session signs the caller straight back in on the next 401); otherwise null,
 * and the component lands on the app root so the bootstrap re-runs without the session.
 */
export async function logoutAction({ request }: ActionFunctionArgs) {
  const api = serverFetch(request);
  const [setCookies, redirectTo] = await Promise.all([
    api
      .post(serviceUrl.postAuthLogout())
      .then((response) => (response.headers["set-cookie"] ?? []) as string[])
      .catch(() => [] as string[]),
    api
      .get<AuthConfig>(serviceUrl.getAuthConfig())
      .then((response) => response.data.signOutUrl || null)
      .catch(() => null),
  ]);
  return data({ redirectTo }, { headers: relaySetCookies(setCookies) });
}
