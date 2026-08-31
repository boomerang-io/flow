/*
 * Browser-side half of the sign-in flow (specifications/authentication.md). Since the 2026-08-31
 * ruling the OIDC protocol itself runs server-side in route actions/loaders (oidc.server.ts);
 * the id_token never reaches the browser. What remains here is what the browser still owns:
 * GET /auth/config consumption (mode selection: none = no sign-in surface, proxy = silent
 * exchange, oidc = a form POST to the server-side sign-in action); proxy mode's single silent
 * exchange (the proxy asserts identity on the browser's own request via forwarded headers, so
 * the empty-body POST must come from the browser); and logout (the response's Set-Cookie
 * clearing the httpOnly session must reach the browser).
 */
import React from "react";
import axios from "axios";
import { serviceUrl } from "Config/servicesConfig";

export type AuthMode = "oidc" | "proxy" | "none";

export interface AuthConfig {
  mode: AuthMode;
  issuer?: string;
  clientId?: string;
  // Proxy mode only: the authenticating proxy's own sign-out URL (flow.signOutUrl on the
  // backend). Logout must chain here after revoking the Flow session - revoking alone is not an
  // exit, because the surviving proxy session silently signs the caller back in on the next 401.
  signOutUrl?: string;
}

/*
 * Thin indirection over the hard-navigation primitive (only `replace` remains - `assign` left
 * with the browser-side authorize redirect), because jsdom's window.location is non-configurable
 * and cannot be spied on directly; specs spy on this property instead.
 */
export const browserNavigation = {
  replace(url: string) {
    window.location.replace(url);
  },
};

export function fetchAuthConfig(): Promise<AuthConfig> {
  return axios.get<AuthConfig>(serviceUrl.getAuthConfig()).then((response) => response.data);
}

/*
 * Browser-side view of GET /auth/config for components that only tune their UI by mode (the
 * Navbar's Sign Out affordance). `null` until resolved - and stays `null` on failure, which
 * callers must treat as "change nothing": a broken config endpoint must never add or remove
 * chrome. Runs in useEffect only, so SSR renders the unchanged default.
 */
export function useAuthConfig(): AuthConfig | null {
  const [config, setConfig] = React.useState<AuthConfig | null>(null);
  React.useEffect(() => {
    let cancelled = false;
    fetchAuthConfig()
      .then((resolved) => {
        if (!cancelled) setConfig(resolved);
      })
      .catch(() => {
        // Leave null - see the contract above.
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return config;
}

/*
 * The proxy path: the proxy has already asserted identity on this request via forwarded headers,
 * so an empty-body POST is all it takes to mint the session cookie. The caller decides what a
 * failure means (show the signed-out page) - and MUST NOT retry in a loop.
 */
export function attemptProxyExchange(): Promise<void> {
  return axios.post(serviceUrl.postAuthExchange()).then(() => undefined);
}

/*
 * Logout: revoke the session server-side AND clear the cookie (the response's Set-Cookie does
 * both - see AuthControllerV2). Callers hard-navigate to "/" afterwards so the bootstrap re-runs
 * without the session. A failure is swallowed on purpose: if the session is already dead, the
 * user is signed out either way, and landing on the bootstrap will say so.
 */
export async function logout(): Promise<void> {
  try {
    await axios.post(serviceUrl.postAuthLogout());
  } catch {
    // Already signed out (or unreachable) - the hard navigation that follows resolves it.
  }
}
