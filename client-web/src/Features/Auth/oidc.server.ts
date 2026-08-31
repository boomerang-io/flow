/*
 * Server-side OIDC sign-in (specifications/authentication.md - maintainer ruling 2026-08-31).
 * The PKCE dance runs HERE, in route action/loader halves on the SSR server, via remix-auth v4 +
 * remix-auth-oauth2 v3 (Arctic underneath) - the browser only ever sees the authorize redirect
 * and the final Set-Cookie relay, so the id_token and code_verifier never reach it.
 *
 * The trust model is a hybrid and Java stays the session authority: this process orchestrates the
 * dance and holds NOTHING beyond two seconds-lived transient flow cookies (the strategy's
 * state+verifier cookie and our nonce+returnPath stash). The verify callback hands the id_token to
 * POST /api/v2/auth/exchange - field names per AuthExchangeRequest.java - where OidcTokenVerifier
 * re-verifies everything (JWKS signature, issuer, audience, expiry, exact-match nonce) and mints
 * the httpOnly bfs_ session cookie, which the callback loader relays VERBATIM onto its redirect.
 *
 * The `.server.ts` suffix makes the bundler enforce what the comment says: none of this - nor
 * remix-auth/arctic - may enter build/client (verified by grep at the migration gates).
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

// One transient cookie holds the in-flight sign-in's nonce + returnPath (the state and PKCE
// verifier live in the strategy's own "flow_oauth2:<id>" cookie). Both are httpOnly, five-minute,
// SameSite=Lax - Lax because the callback arrives as a top-level GET navigation from the issuer.
const SIGNIN_STASH_COOKIE = "flow_auth_signin";
const STRATEGY_COOKIE = "flow_oauth2";
const TRANSIENT_COOKIE = {
  httpOnly: true,
  secure: true, // same posture as the Java SessionCookie; browsers allow Secure on localhost
  sameSite: "Lax",
  path: "/",
  maxAge: 60 * 5,
} as const;

/*
 * remix-auth-oauth2 sends state + PKCE but not the OIDC nonce; authorizationParams() is its
 * documented extension point for exactly this kind of provider-required extra parameter. The
 * nonce is set per instance - safe because a strategy is built per request (see buildStrategy).
 */
class FlowOidcStrategy extends OAuth2Strategy<SessionRelay> {
  name = "flow-oidc";
  nonce: string | null = null;

  protected override authorizationParams(params: URLSearchParams): URLSearchParams {
    const next = new URLSearchParams(params);
    if (this.nonce) next.set("nonce", this.nonce);
    return next;
  }
}

// 32 random bytes -> 43 base64url chars, same entropy the browser-side flow used for its nonce.
// (Spelled out via base64 because the pinned @types/node predates the "base64url" encoding name.)
function randomNonce(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/*
 * The redirect_uri must be byte-identical at the authorize and token steps (RFC 6749 §4.1.3) and
 * must match what the IDP registered (docker/idpzero/server.yaml: {origin}/apps/flow/auth/callback).
 * Both are derived from the request's own URL - origin included, which is correct behind proxies
 * because server/index.js builds the Request from the forwarded Host (trust proxy) - so this works
 * for any basename without a config knob. `.data` is stripped defensively; both legs are document
 * navigations, so it should never appear.
 */
function callbackUriFor(request: Request): string {
  const url = new URL(request.url);
  const pathname = url.pathname.replace(/\.data$/, "").replace(/\/auth\/signin$/, "/auth/callback");
  return `${url.origin}${pathname}`;
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
  if (!nonce) return null;
  return { nonce, returnPath: value.get("returnPath") ?? "" };
}

/*
 * Expires every transient flow cookie the browser sent - the flow is one-shot either way.
 * Epoch `Expires` rather than `Max-Age=0` because SetCookie drops a falsy maxAge from its
 * serialisation.
 */
function expiredTransientCookies(request: Request): string[] {
  const cookie = new Cookie(request.headers.get("cookie") ?? "");
  return cookie.names
    .filter((name) => name === SIGNIN_STASH_COOKIE || name.startsWith(STRATEGY_COOKIE))
    .map((name) =>
      new SetCookie({ name, value: "", ...TRANSIENT_COOKIE, maxAge: undefined, expires: new Date(0) }).toString(),
    );
}

/*
 * Builds the strategy PER REQUEST: the issuer/clientId come from GET /auth/config, which is
 * settings-backed and may change at runtime - freezing it at module scope is the bug this repo
 * already shipped once with moment() defaults. The config endpoint is unauthenticated, so the
 * serverFetch cookie forwarding is incidental. OAuth2Strategy.discover then resolves the issuer's
 * own discovery document (authorize/token endpoints) fresh on every call for the same reason.
 */
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
      // Java is the verifier and the session authority: hand it the id_token + the nonce we sent
      // on the authorize request, and relay back whatever Set-Cookie it minted. axios throws on a
      // non-2xx exchange, which surfaces as the loader's readable error - never a minted session.
      const nonce = readSigninStash(callbackRequest)?.nonce ?? "";
      const exchange = await serverFetch(callbackRequest).post(serviceUrl.postAuthExchange(), {
        idToken: tokens.idToken(),
        nonce,
      });
      const setCookies = exchange.headers["set-cookie"] ?? [];
      if (setCookies.length === 0) {
        throw new Error("The sign-in exchange returned no session cookie.");
      }
      return { setCookies };
    },
  );
}

/*
 * The sign-in leg: the SignedOut page's <Form method="post"> lands here. The strategy throws its
 * authorize redirect (state + S256 challenge + our nonce), and this action appends the stash
 * cookie to it. Any failure redirects to this route's own GET surface with a readable error -
 * never a silent blank page, and never an automatic retry loop.
 */
export async function signinAction({ request }: ActionFunctionArgs): Promise<Response> {
  const formData = await request.clone().formData();
  const returnPath = safeReturnPath(formData.get("returnPath"), request);
  const errorPath = `${new URL(request.url).pathname}?error=start`;
  let authenticator: Authenticator<SessionRelay>;
  const nonce = randomNonce();
  try {
    const strategy = await buildStrategy(request);
    strategy.nonce = nonce;
    authenticator = new Authenticator<SessionRelay>().use(strategy, "oidc");
  } catch {
    return redirect(errorPath);
  }
  try {
    await authenticator.authenticate("oidc", request);
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

/*
 * The callback leg: the issuer redirects back here with ?code&state (a top-level GET, so this
 * always runs server-side as a document load). The strategy checks state against its cookie and
 * exchanges the code; the verify callback above does the Java exchange. On success: redirect to
 * the stashed return path carrying the relayed session Set-Cookie, transient cookies expired. On
 * ANY failure: readable error data for the route component - deliberately NO automatic sign-in
 * retry from here, which is exactly how redirect loops are built.
 */
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
