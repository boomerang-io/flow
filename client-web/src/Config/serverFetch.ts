import axios, { type AxiosInstance } from "axios";

/*
 * Server-side counterpart to Config/servicesConfig.ts's browser `resolver`/`axios` calls. Only
 * ever imported from a route's `loader`/`action` export (see app/routes/globalParameters.tsx,
 * app/routes/userDetailed.tsx, app/root.tsx's root loader - implemented in
 * Features/App/App.tsx) - react-router's
 * route-module splitting (v8_splitRouteModules, see react-router.config.ts) strips
 * loader/action-only code out of the client bundle, so this file's Node-only assumptions
 * (process.env, no browser cookie jar) never ship to the browser.
 *
 * Two API base URLs (flagged explicitly per the SSR migration direction): the browser talks to
 * PRODUCT_SERVICE_ENV_URL/CORE_SERVICE_ENV_URL - public, injected into `window._SERVER_DATA` by
 * the existing server/ Express server at container boot (see servicesConfig.ts) - which is not
 * necessarily reachable from *this* Node process (a different network path, e.g. a public
 * ingress vs. cluster-internal service DNS). CORE_SERVICE_INTERNAL_ORIGIN is a new, separate
 * runtime env var for that internal address, read directly via `process.env` (not
 * `import.meta.env`, which Vite inlines at build time - this needs to vary per deploy without a
 * rebuild). Unset by default: nothing currently configures it, and there's no internal address
 * to point at yet, so every call below fails fast (invalid/empty base URL) and the caller's
 * existing try/catch falls back to today's behaviour. This is the "wire one for now" case the
 * SSR migration review flagged - only one of the two base URLs actually resolves anywhere today.
 */
const INTERNAL_API_ORIGIN = process.env.CORE_SERVICE_INTERNAL_ORIGIN ?? "";

/*
 * specifications/authentication.md (🔵 PROPOSED, 2026-08-18): `POST /api/v2/auth/exchange` mints
 * an httpOnly, Secure, SameSite=Lax session cookie carrying an opaque `bfs_<uuid>`, which
 * `AuthenticationFilter` is gaining a cookie-reading branch for. That endpoint is not merged as
 * of this change, so everything here is coded to the documented contract but UNVERIFIED
 * end-to-end - there is no live session to authenticate a real server loader call against yet.
 * The browser's axios instance relies on `withCredentials: true` (see servicesConfig.ts) to send
 * cookies automatically; that browser cookie jar doesn't exist in Node, so the incoming request's
 * `Cookie` header is read off the *inbound* SSR `Request` and forwarded explicitly on the
 * *outbound* API call instead. Do not invent a different auth mechanism here - this is the one
 * the maintainer directed loaders to use.
 */
/*
 * The URL builders in servicesConfig.ts prefix every path with `/api`, which reaches the real
 * `/api/v2` routes two different ways in the browser: the dev proxy rewrites it, and the
 * production server injects the versioned value into window._SERVER_DATA. Node has neither, so a
 * server-side call would hit `/api/...` and 404. Apply the same rewrite the dev proxy applies.
 */
function toVersionedPath(url?: string): string | undefined {
  // The mock server used by the test suite registers its routes at the unversioned paths the URL
  // builders produce, so it intercepts before any rewrite would apply - skip it there.
  if (process.env.VITEST) {
    return url;
  }
  return url?.startsWith("/api/") && !url.startsWith("/api/v2/") ? url.replace(/^\/api\//, "/api/v2/") : url;
}

export function serverFetch(request: Request): AxiosInstance {
  const cookie = request.headers.get("cookie");
  const instance = axios.create({
    baseURL: INTERNAL_API_ORIGIN,
    timeout: 5000,
    headers: cookie ? { Cookie: cookie } : undefined,
  });
  instance.interceptors.request.use((config) => ({ ...config, url: toVersionedPath(config.url) }));
  return instance;
}
