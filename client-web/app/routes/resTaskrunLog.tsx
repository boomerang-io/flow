import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF STREAMING resource route (no default export): GET /res/taskrun/:id/log pipes
 * GET /api/v2/taskrun/{id}/log from service-core to the browser. Backs TaskRunLog's LazyLog
 * surface, which follows a live log - the response body must flow through, never accumulate.
 *
 * Native `fetch` (undici) rather than serverFetch's axios instance, for three load-bearing
 * reasons:
 *  - undici's `Response.body` IS a web ReadableStream, which is exactly what a react-router
 *    loader Response wants to carry; an axios `responseType: "stream"` body is a Node Readable
 *    that would need an extra wrap (and axios buffers by default without that option).
 *  - serverFetch sets `timeout: 5000` on every call - right for JSON loaders, fatal for a
 *    follow-mode log stream that legitimately stays open for minutes.
 *  - `request.signal` aborts when the browser goes away (server/index.js wires
 *    `res.on("close") -> controller.abort()`), and undici propagates that abort straight to the
 *    upstream socket, so closing the modal stops the service-core transfer too.
 *
 * Non-buffering by construction: `upstream.body` is handed to the returned Response untouched.
 * react-router serves a resource-route loader's Response as-is, and the Express bridge
 * (server/index.js sendFetchResponse) pipes any non-HTML body chunk-by-chunk via
 * writeReadableStreamToWritable with reader backpressure. No layer between the service-core
 * socket and the browser socket ever holds more than the in-flight chunk.
 */

/*
 * serverFetch.ts owns the internal-origin + versioned-path rules for its axios instance; this
 * route bypasses axios (see above) so it applies the same two rules inline: absolute base from
 * CORE_SERVICE_INTERNAL_ORIGIN (read per-request off process.env - Node-only, varies per deploy
 * without a rebuild), and the dev-proxy `/api/` -> `/api/v2/` rewrite, skipped under VITEST
 * where the MSW handler layer registers the unversioned shape (see serverFetch.ts's
 * toVersionedPath comment for the full reasoning).
 */
function toVersionedPath(url: string): string {
  if (process.env.VITEST) {
    return url;
  }
  return url.startsWith("/api/") && !url.startsWith("/api/v2/") ? url.replace(/^\/api\//, "/api/v2/") : url;
}

export async function loader({ request, params }: { request: Request; params: { id: string } }) {
  const origin = process.env.CORE_SERVICE_INTERNAL_ORIGIN ?? "";
  const cookie = request.headers.get("cookie");

  let upstream: Response;
  try {
    upstream = await fetch(`${origin}${toVersionedPath(serviceUrl.getTaskrunLog({ id: params.id }))}`, {
      headers: cookie ? { cookie } : undefined,
      signal: request.signal,
    });
  } catch {
    // Unreachable/unconfigured service-core (empty CORE_SERVICE_INTERNAL_ORIGIN makes the URL
    // relative, which Node's fetch rejects outright - the same fail-fast serverFetch documents).
    return new Response("log unavailable", { status: 502, headers: { "content-type": "text/plain; charset=utf-8" } });
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "text/plain; charset=utf-8" },
  });
}
