/*
 * Browser-side sign-in flow (specifications/authentication.md). Everything in this module runs in
 * the browser only - the OIDC discovery fetch, the PKCE code exchange at the issuer, and the
 * final POST to our own /auth/exchange are all made by the browser itself, so the id_token never
 * transits the SSR server. The session that results is an httpOnly bfs_ cookie set by the
 * exchange response; client JS never sees the token.
 *
 * Three modes, served by GET /auth/config (unauthenticated):
 *   none  - security-off dev stack: no sign-in surface at all.
 *   proxy - an authenticating reverse proxy asserts identity via forwarded headers; a single
 *           empty-body POST to /auth/exchange converts that into a session cookie.
 *   oidc  - browser-side PKCE (public client, no secret) against the configured issuer, then
 *           POST {idToken, nonce} to /auth/exchange (field names per AuthExchangeRequest.java).
 */
import React from "react";
import axios from "axios";
import { APP_ROOT } from "Config/appConfig";
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

// One sessionStorage slot holds the whole in-flight sign-in. It is written by
// buildOidcAuthorizeRedirect and consumed (one-shot - removed on first read) by
// completeOidcCallback; nothing else may touch it.
export const AUTH_STASH_KEY = "boomerang-flow-auth-signin";

interface SignInStash {
  state: string;
  nonce: string;
  verifier: string;
  clientId: string;
  tokenEndpoint: string;
  returnPath: string;
}

/*
 * Thin indirection over the two hard-navigation primitives, because jsdom's window.location is
 * non-configurable and cannot be spied on directly. Production behaviour is identical to calling
 * window.location.assign/replace; specs spy on these properties instead.
 */
export const browserNavigation = {
  assign(url: string) {
    window.location.assign(url);
  },
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

// The redirect_uri must be byte-identical at the authorize and token steps (RFC 6749 §4.1.3).
export function oidcCallbackUri(): string {
  return `${window.location.origin}${APP_ROOT}/auth/callback`;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// 32 random bytes -> 43 base64url chars, which satisfies RFC 7636 §4.1's 43-128 char verifier
// requirement and is comfortably enough entropy for state/nonce too.
function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

// S256 code challenge (RFC 7636 §4.2) via Web Crypto - no dependency needed.
export async function s256Challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

interface OidcDiscovery {
  authorization_endpoint: string;
  token_endpoint: string;
}

/*
 * The issuer is browser-reachable by design (the whole point of the public-client PKCE flow), so
 * discovery is a plain browser fetch. Deliberately credential-less: our session cookie has no
 * business at the issuer.
 */
async function fetchDiscovery(issuer: string): Promise<OidcDiscovery> {
  const discoveryUrl = `${issuer.replace(/\/$/, "")}/.well-known/openid-configuration`;
  const response = await fetch(discoveryUrl, { credentials: "omit" });
  if (!response.ok) {
    throw new Error(`The identity provider's discovery document could not be loaded (HTTP ${response.status}).`);
  }
  const discovery = (await response.json()) as Partial<OidcDiscovery>;
  if (!discovery.authorization_endpoint || !discovery.token_endpoint) {
    throw new Error("The identity provider's discovery document is missing its authorization or token endpoint.");
  }
  return { authorization_endpoint: discovery.authorization_endpoint, token_endpoint: discovery.token_endpoint };
}

/*
 * Step 1 of the OIDC flow: discovery, fresh state/nonce/verifier, stash, and the authorize URL.
 * Returns the URL rather than navigating so the caller owns the redirect (and so specs can
 * assert on it without fighting jsdom's location).
 */
export async function buildOidcAuthorizeRedirect(config: AuthConfig, returnPath: string): Promise<string> {
  if (!config.issuer || !config.clientId) {
    throw new Error("The sign-in configuration is incomplete: an OIDC issuer and clientId are required.");
  }
  const discovery = await fetchDiscovery(config.issuer);
  const state = randomToken();
  const nonce = randomToken();
  const verifier = randomToken();
  const challenge = await s256Challenge(verifier);

  const stash: SignInStash = {
    state,
    nonce,
    verifier,
    clientId: config.clientId,
    tokenEndpoint: discovery.token_endpoint,
    returnPath,
  };
  sessionStorage.setItem(AUTH_STASH_KEY, JSON.stringify(stash));

  const authorizeUrl = new URL(discovery.authorization_endpoint);
  authorizeUrl.searchParams.set("response_type", "code");
  authorizeUrl.searchParams.set("client_id", config.clientId);
  authorizeUrl.searchParams.set("redirect_uri", oidcCallbackUri());
  authorizeUrl.searchParams.set("scope", "openid profile email");
  authorizeUrl.searchParams.set("state", state);
  authorizeUrl.searchParams.set("nonce", nonce);
  authorizeUrl.searchParams.set("code_challenge", challenge);
  authorizeUrl.searchParams.set("code_challenge_method", "S256");
  return authorizeUrl.toString();
}

// The stash's returnPath round-trips through sessionStorage, so treat it as untrusted on the way
// back out: only a same-origin absolute path may be navigated to (never "//host" or a full URL).
function safeReturnPath(candidate: unknown): string {
  if (typeof candidate === "string" && candidate.startsWith("/") && !candidate.startsWith("//")) {
    return candidate;
  }
  return `${APP_ROOT}/`;
}

/*
 * Step 2 of the OIDC flow, run from the /auth/callback route. Validates state against the stash
 * (consumed one-shot - an authorization code is single-use anyway, so a failed attempt always
 * restarts from the sign-in page rather than replaying the stash), exchanges the code at the
 * issuer's token endpoint with the PKCE verifier (public client - client_id only, no secret),
 * then POSTs {idToken, nonce} to our own exchange, whose response sets the session cookie.
 */
export async function completeOidcCallback(search: string): Promise<{ returnPath: string }> {
  const params = new URLSearchParams(search);
  const providerError = params.get("error");
  if (providerError) {
    throw new Error(`The identity provider returned an error: ${providerError}.`);
  }
  const code = params.get("code");
  const state = params.get("state");
  if (!code || !state) {
    throw new Error("The sign-in callback is missing its code or state parameter.");
  }

  const rawStash = sessionStorage.getItem(AUTH_STASH_KEY);
  sessionStorage.removeItem(AUTH_STASH_KEY);
  if (!rawStash) {
    throw new Error("No sign-in is in progress in this browser tab. Start again from the sign-in page.");
  }
  let stash: SignInStash;
  try {
    stash = JSON.parse(rawStash) as SignInStash;
  } catch {
    throw new Error("The stored sign-in state could not be read. Start again from the sign-in page.");
  }
  if (!stash.state || stash.state !== state) {
    throw new Error("The sign-in response did not match this browser tab's sign-in request (state mismatch).");
  }

  const tokenResponse = await fetch(stash.tokenEndpoint, {
    method: "POST",
    credentials: "omit",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: oidcCallbackUri(),
      client_id: stash.clientId,
      code_verifier: stash.verifier,
    }),
  });
  if (!tokenResponse.ok) {
    throw new Error(`The identity provider rejected the sign-in code (HTTP ${tokenResponse.status}).`);
  }
  const tokens = (await tokenResponse.json()) as { id_token?: string };
  if (!tokens.id_token) {
    throw new Error("The identity provider's response did not include an id_token.");
  }

  // Field names match AuthExchangeRequest.java. The backend re-verifies everything (JWKS
  // signature, issuer, audience, expiry) plus that the token's nonce claim matches this value.
  await axios.post(serviceUrl.postAuthExchange(), { idToken: tokens.id_token, nonce: stash.nonce });

  return { returnPath: safeReturnPath(stash.returnPath) };
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
