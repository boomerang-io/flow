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
 * Two API base URLs: the browser-facing PRODUCT_SERVICE_ENV_URL - public, written into
 * `window._SERVER_DATA` by the root loader (app/serverData.server.ts, see servicesConfig.ts)
 * and only used where the app surfaces a copyable API URL - is not necessarily reachable from
 * *this* Node process (a different network path, e.g. a public ingress vs. cluster-internal
 * service DNS). CORE_SERVICE_INTERNAL_ORIGIN is the separate runtime env var for that internal
 * address, read directly via `process.env` (not `import.meta.env`, which Vite inlines at build
 * time - this needs to vary per deploy without a rebuild). docker-compose.yml sets it to
 * http://service-core:7700 and a Kubernetes Deployment sets it to the service-core Service; when
 * it is unset every call below fails fast (empty base URL) and the caller's try/catch degrades
 * to its error state rather than failing the render. The dev server (`pnpm dev`) alone defaults
 * to service-core on the host, so it runs with no environment to set. Vitest also runs with
 * NODE_ENV=development (Vite sets it), so the default is withheld there: the specs' MSW handlers
 * match the relative paths an empty origin produces.
 */
// Evaluated, never called, in a browser under the dev server: the production build tree-shakes
// this module out of the client bundle (the loaders that import it are stripped from route
// modules and `sideEffects: false` lets the unused import go), but the dev server serves
// Features/App/App.tsx whole, and a bare `process` there would stop the whole app from hydrating.
const env: NodeJS.ProcessEnv = typeof process !== "undefined" ? process.env : {};
const isDevServer = env.NODE_ENV === "development" && !env.VITEST;

/** Read per call: the resource routes that stream or proxy bodies resolve it per request. */
export function internalApiOrigin(): string {
  return env.CORE_SERVICE_INTERNAL_ORIGIN ?? (isDevServer ? "http://localhost:7700" : "");
}

const INTERNAL_API_ORIGIN = internalApiOrigin();

/*
 * `POST /internal/v1/auth/exchange` mints an httpOnly, Secure, SameSite=Lax session cookie carrying
 * an opaque `bfs_<uuid>`, which `AuthenticationFilter` reads. Verified end-to-end against a
 * secured compose stack: an SSR fetch of /home carrying only the session Cookie header returns
 * the authenticated render.
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
  // Checked at the MSW switchover (ApiServer/msw/handlers.ts): the mock layer builds every route
  // pattern by calling the app's own `serviceUrl` functions (see that file's module doc), which
  // always emit the unversioned `/api/...` shape `BASE_URL` produces outside a real production
  // browser (see servicesConfig.ts - `BASE_URL` only becomes a versioned origin when
  // `window._SERVER_DATA` is present, which is a prod-browser-only condition). So MSW's handlers
  // register unversioned, exactly like Mirage's did before it - the premise for dropping this
  // guard ("if the mock layer registers at versioned paths...") doesn't hold, switchover or not.
  //
  // The same `handlers` array/MSW server also now intercepts this file's own Node-side calls (see
  // the SSR-loader-in-Node harness, e.g. Settings.loader.node.spec.ts), in the same test process
  // as every component spec that calls `resolver`/`axios` directly off `serviceUrl` (never
  // rewritten - there's no runtime interceptor on that path, in tests or in a real browser
  // outside the dev proxy). Keeping this rewrite skipped under VITEST is what keeps both
  // consumers on the one path shape the shared handlers list actually matches; rewriting only
  // this file's calls would desync it from every other spec instead of the two path shapes
  // agreeing, which is the actual goal here (see CLAUDE.md's switchover task notes).
  if (env.VITEST) {
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
