/*
 * Shared auth vocabulary + the one navigation seam the browser still owns. Since the BFF slice
 * (2026-09-01) the browser makes NO /api auth calls at all: GET /auth/config is fetched by the
 * root loader (Features/App/App.tsx) and handed down as loader data, the proxy silent exchange
 * and logout are route actions (session.server.ts), and the OIDC dance runs server-side
 * (oidc.server.ts). The axios calls that used to live here (fetchAuthConfig, useAuthConfig,
 * attemptProxyExchange, logout) are gone with their callers.
 */

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
 * Thin indirection over the hard-navigation primitive (AuthLogout's post-revoke exit), because
 * jsdom's window.location is non-configurable and cannot be spied on directly; specs spy on this
 * property instead.
 */
export const browserNavigation = {
  replace(url: string) {
    window.location.replace(url);
  },
};
