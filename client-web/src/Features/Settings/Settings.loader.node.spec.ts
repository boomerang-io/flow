// @vitest-environment node
//
// Proves the net-new capability this MSW migration exists for: intercepting the `fetch` calls a
// route module's `loader`/`action` make directly in a real Node environment via `serverFetch`
// (Config/serverFetch.ts) - the SSR path (see app/entry.server.tsx / react-router.config.ts).
// Mirage patches the global `XMLHttpRequest`, which only exists in a browser/jsdom document, so
// it could never intercept this path at all; every other spec that calls a route's `loader`/
// `action` directly (AdminTasks.spec.tsx, Settings.spec.tsx, etc.) still runs under vitest's
// default `jsdom` environment, where the same MSW server also happens to work because jsdom's own
// XHR/fetch ultimately goes through Node's network stack - so those specs don't actually prove
// Node interception on their own. This file does, by setting vitest's per-file environment
// pragma above to `node`: no `window`, no `document`, no jsdom XHR shim, nothing but the plain
// Node process `serverFetch` itself runs in when deployed. `src/setupTests.tsx` (vitest's global
// setupFile) still runs for this file - it centralises the same `server.listen()`/
// `resetHandlers()`+`resetDb()`/`server.close()` MSW lifecycle every other spec gets, plus a
// `typeof document !== "undefined"` guard added for exactly this file's benefit.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { db } from "ApiServer/msw/db";

// serverFetch.ts's axios instance reads `CORE_SERVICE_INTERNAL_ORIGIN` into a module-level
// `const` once, at import time (see its own comment: unset by default, so every call "fails
// fast" today - a documented, pending piece of the SSR migration, not a bug this spec works
// around). A relative URL with an empty `baseURL` is what a *browser* environment resolves
// against `window.location`; plain Node has no such fallback, so give the module a real origin
// to build absolute URLs against before importing it, the standard `vi.stubEnv` +
// `vi.resetModules()` + dynamic import pattern for exercising an env-gated module constant.
const INTERNAL_ORIGIN = "http://core-service.internal";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("Settings loader --- Node SSR", () => {
  it("resolves settings through serverFetch, intercepted by MSW's Node server (not jsdom XHR)", async () => {
    expect(typeof window).toBe("undefined");

    // handlers.ts registers every route as a *relative* pattern (built off `serviceUrl`'s own
    // `BASE_URL`, which is always relative - see its module doc) - MSW deliberately leaves a
    // relative pattern relative in Node rather than resolving it against a fake default origin
    // (there's no `window.location` to resolve it against, unlike jsdom), so it only matches a
    // request made to that same bare path. `serverFetch` builds a real absolute URL instead
    // (`INTERNAL_ORIGIN` + the path), which is what a real Node SSR deployment does - so this
    // registers the one-off absolute-URL override that scenario needs, proving MSW intercepts a
    // plain Node `http`/`fetch` call to an arbitrary origin, not just the jsdom-implicit one
    // every other spec incidentally exercises.
    server.use(http.get(`${INTERNAL_ORIGIN}/api/settings`, () => HttpResponse.json(db.settings)));

    const { loader } = await import("./Settings");
    const request = new Request("http://localhost/admin/settings");
    const result = await loader({ request });

    expect(result.errorLoading).toBe(false);
    expect(result.settings).toEqual(db.settings);
  });
});
