/*
 * Server-side OIDC sign-in (specifications/authentication.md, maintainer ruling 2026-08-31): the
 * PKCE dance runs here in route action/loader halves via remix-auth v4 + remix-auth-oauth2 v3
 * (Arctic underneath) - the browser only ever sees the authorize redirect and the final
 * Set-Cookie relay, so the id_token and code_verifier never reach it. Java stays the verifier
 * and session authority: the verify callback POSTs {idToken, nonce} (field names per
 * AuthExchangeRequest.java) to POST /api/v2/auth/exchange, and the bfs_ Set-Cookie it mints is
 * relayed VERBATIM onto the redirect. This process holds nothing beyond the two five-minute
 * httpOnly flow cookies below. Standard OIDC only - nothing keyed to any particular provider.
 * The `.server.ts` suffix keeps all of this (and remix-auth/arctic) out of build/client.
 */
import { Cookie, SetCookie } from "@mjackson/headers";
import { redirect, type ActionFunctionArgs, type LoaderFunctionArgs } from "react-router";
import { Authenticator } from "remix-auth";
import { OAuth2Strategy } from "remix-auth-oauth2";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type { AuthConfig } from "./authClient";

/** What the verify callback returns: the Java exchange's Set-Cookie header(s), relayed onward. */
interface SessionRelay {
  setCookies: string[];
}

// The strategy's own cookie holds state + PKCE verifier; ours holds nonce + returnPath. Both are
// httpOnly, SameSite=Lax (the callback arrives as a top-level GET navigation from the issuer),
// Secure (same posture as the Java SessionCookie; browsers allow Secure on localhost).
const SIGNIN_STASH_COOKIE = "flow_auth_signin";
const STRATEGY_COOKIE = "flow_oauth2";
const TRANSIENT_COOKIE = { httpOnly: true, secure: true, sameSite: "Lax", path: "/", maxAge: 60 * 5 } as const;

// remix-auth-oauth2 sends state + PKCE but not the OIDC nonce; authorizationParams() is its
// documented extension point for provider-required extras. Per-instance is safe: a strategy is
// built per request (see buildStrategy).
class FlowOidcStrategy extends OAuth2Strategy<SessionRelay> {
  name = "flow-oidc";
  nonce: string | null = null;

  protected override authorizationParams(params: URLSearchParams): URLSearchParams {
    const next = new URLSearchParams(params);
    if (this.nonce) next.set("nonce", this.nonce);
    return next;
  }
}

// 32 random bytes -> 43 base64url chars (spelled out via base64: the pinned @types/node
// predates the "base64url" encoding name).
function randomNonce(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// The redirect_uri must be byte-identical at the authorize and token steps (RFC 6749 §4.1.3)
// and match the IdP's registration. Both derive from the request's own URL - origin included,
// which holds behind proxies because server/index.js builds the Request from the forwarded Host
// - so any basename works without a config knob. `.data` stripped defensively (both legs are
// document navigations).
function callbackUriFor(request: Request): string {
  const url = new URL(request.url);
  return `${url.origin}${url.pathname.replace(/\.data$/, "").replace(/\/auth\/signin$/, "/auth/callback")}`;
}

/** The app root for this deployment, derived the same way: strip the auth route's own segments. */
function appRootPathFor(request: Request): string {
  return new URL(request.url).pathname.replace(/\.data$/, "").replace(/\/auth\/(signin|callback)$/, "/");
}

// The returnPath round-trips through a browser cookie, so it is untrusted on the way back out:
// only a same-origin absolute path may be navigated to (never "//host" or a full URL).
function safeReturnPath(candidate: unknown, request: Request): string {
  if (typeof candidate === "string" && candidate.startsWith("/") && !candidate.startsWith("//")) {
    return candidate;
  }
  return appRootPathFor(request);
}

function writeSigninStash(nonce: string, returnPath: string): string {
  const value = new URLSearchParams({ nonce, returnPath });
  return new SetCookie({ name: SIGNIN_STASH_COOKIE, value: value.toString(), ...TRANSIENT_COOKIE }).toString();
}

function readSigninStash(request: Request): { nonce: string; returnPath: string } | null {
  const raw = new Cookie(request.headers.get("cookie") ?? "").get(SIGNIN_STASH_COOKIE);
  if (!raw) return null;
  const value = new URLSearchParams(raw);
  const nonce = value.get("nonce");
  return nonce ? { nonce, returnPath: value.get("returnPath") ?? "" } : null;
}

// Expires every transient flow cookie the browser sent - the flow is one-shot either way. Epoch
// `Expires` rather than `Max-Age=0` because SetCookie drops a falsy maxAge from serialisation.
function expiredTransientCookies(request: Request): string[] {
  return new Cookie(request.headers.get("cookie") ?? "").names
    .filter((name) => name === SIGNIN_STASH_COOKIE || name.startsWith(STRATEGY_COOKIE))
    .map((name) =>
      new SetCookie({ name, value: "", ...TRANSIENT_COOKIE, maxAge: undefined, expires: new Date(0) }).toString(),
    );
}

// Builds the strategy PER REQUEST: issuer/clientId come from GET /auth/config, settings-backed
// and changeable at runtime - freezing it at module scope is the bug this repo already shipped
// once with moment() defaults. discover() re-resolves the OIDC discovery document likewise.
async function buildStrategy(request: Request): Promise<FlowOidcStrategy> {
  const { data: config } = await serverFetch(request).get<AuthConfig>(serviceUrl.getAuthConfig());
  if (config.mode !== "oidc" || !config.issuer || !config.clientId) {
    throw new Error(`Sign-in is not configured for OIDC (mode "${config.mode}").`);
  }
  return FlowOidcStrategy.discover<SessionRelay, FlowOidcStrategy>(
    config.issuer,
    {
      clientId: config.clientId,
      clientSecret: null, // public client: PKCE only, client_id goes in the token-request body
      redirectURI: callbackUriFor(request),
      scopes: ["openid", "profile", "email"],
      cookie: { name: STRATEGY_COOKIE, ...TRANSIENT_COOKIE },
    },
    async ({ request: callbackRequest, tokens }) => {
      // Java verifies (JWKS, iss/aud/exp, exact-match nonce) and mints; axios throws on a non-2xx
      // exchange, which surfaces as the loader's readable error - never a minted session.
      const exchange = await serverFetch(callbackRequest).post(serviceUrl.postAuthExchange(), {
        idToken: tokens.idToken(),
        nonce: readSigninStash(callbackRequest)?.nonce ?? "",
      });
      const setCookies = exchange.headers["set-cookie"] ?? [];
      if (setCookies.length === 0) throw new Error("The sign-in exchange returned no session cookie.");
      return { setCookies };
    },
  );
}

// The sign-in leg: the SignedOut page's <Form method="post"> lands here; the strategy throws
// its authorize redirect (state + S256 challenge + our nonce) and the stash cookie is appended.
// Any failure redirects to this route's own GET surface with a readable error - never a silent
// blank page, never an automatic retry loop.
export async function signinAction({ request }: ActionFunctionArgs): Promise<Response> {
  const formData = await request.clone().formData();
  const returnPath = safeReturnPath(formData.get("returnPath"), request);
  const errorPath = `${new URL(request.url).pathname}?error=start`;
  const nonce = randomNonce();
  try {
    const strategy = await buildStrategy(request);
    strategy.nonce = nonce;
    await new Authenticator<SessionRelay>().use(strategy, "oidc").authenticate("oidc", request);
  } catch (thrown) {
    if (thrown instanceof Response && thrown.headers.has("location")) {
      thrown.headers.append("Set-Cookie", writeSigninStash(nonce, returnPath));
      return thrown;
    }
    return redirect(errorPath);
  }
  // The start leg always throws its redirect; falling through means the strategy misbehaved.
  return redirect(errorPath);
}

/** GET /auth/signin renders the error surface after a failed start; otherwise back to the app. */
export async function signinLoader({ request }: LoaderFunctionArgs) {
  if (new URL(request.url).searchParams.has("error")) {
    return { error: "Sign-in could not be started - the sign-in configuration or the identity provider could not be reached." };
  }
  return redirect(appRootPathFor(request));
}

// The callback leg: the issuer redirects back with ?code&state (a top-level GET document load,
// so this always runs server-side). The strategy checks state and exchanges the code; the verify
// callback above does the Java exchange. Success: redirect to the stashed return path carrying
// the relayed session Set-Cookie, transients expired. ANY failure: readable error data for the
// route component - deliberately NO automatic retry, which is how redirect loops are built.
export async function callbackLoader({ request }: LoaderFunctionArgs) {
  const params = new URL(request.url).searchParams;
  const providerError = params.get("error");
  if (providerError) {
    return { error: `The identity provider returned an error: ${providerError}.` };
  }
  if (!params.get("code") || !params.get("state")) {
    return { error: "The sign-in callback is missing its code or state parameter." };
  }
  const stash = readSigninStash(request);
  if (!stash) {
    return { error: "No sign-in is in progress in this browser. Start again from the sign-in page." };
  }

  let session: SessionRelay;
  try {
    const strategy = await buildStrategy(request);
    session = await new Authenticator<SessionRelay>().use(strategy, "oidc").authenticate("oidc", request);
  } catch (thrown) {
    // With ?state present the strategy never redirects, so any Response here is unexpected -
    // rethrow rather than swallow. RangeError is its state-mismatch signal (lib/store.js).
    if (thrown instanceof Response) throw thrown;
    if (thrown instanceof RangeError) {
      return { error: "The sign-in response did not match this browser's sign-in request (state mismatch)." };
    }
    return { error: "Sign-in could not be completed with the identity provider. Start again from the sign-in page." };
  }

  const headers = new Headers({ Location: safeReturnPath(stash.returnPath, request) });
  for (const setCookie of session.setCookies) headers.append("Set-Cookie", setCookie);
  for (const expired of expiredTransientCookies(request)) headers.append("Set-Cookie", expired);
  return new Response(null, { status: 302, headers });
}
