// @vitest-environment node
//
// Runs the /profile route action in a REAL Node environment (no jsdom, no window, no browser
// cookie jar) - the environment it actually executes in under ssr:true. That is the whole point:
// the previous implementation issued its writes with the browser `resolver`/`axios` instance,
// which authenticates purely via `axios.defaults.withCredentials` (Config/axiosGlobalConfig.ts).
// `withCredentials` needs a browser cookie jar to have anything to send, so the same call made
// from a server action goes out with NO credentials at all. These tests pin the replacement
// behaviour: every outbound call carries the caller's inbound session cookie, and neither write
// can be pointed at another user.
//
// See Features/Settings/Settings.loader.node.spec.ts for the env-stubbing rationale.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";

const INTERNAL_ORIGIN = "http://core-service.internal";
const SESSION_COOKIE = "bfs_session=abc123";
const CURRENT_USER_ID = "current-user-id";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

/** Builds the POST the browser's useFetcher() submission arrives as, with a session cookie. */
function actionRequest(fields: Record<string, string>, { cookie = SESSION_COOKIE } = {}) {
  const body = new URLSearchParams(fields);
  return new Request("http://localhost/profile", {
    method: "POST",
    headers: cookie ? { cookie, "content-type": "application/x-www-form-urlencoded" } : undefined,
    body,
  });
}

describe("UserProfile action --- Node SSR", () => {
  it("updates the profile via the session-scoped PATCH /profile, forwarding the caller's cookie", async () => {
    expect(typeof window).toBe("undefined");

    const seen: { cookie: string | null; body: unknown } = { cookie: null, body: null };
    server.use(
      http.patch(`${INTERNAL_ORIGIN}/api/profile`, async ({ request }) => {
        seen.cookie = request.headers.get("cookie");
        seen.body = await request.json();
        return HttpResponse.json({});
      }),
    );

    const { action } = await import("./UserProfile");
    const result = await action({
      request: actionRequest({ intent: "updateProfile", displayName: "Ada" }),
    });

    expect(result).toEqual({ ok: true, intent: "updateProfile" });
    // The credential the API authenticates on. Without this the request is anonymous.
    expect(seen.cookie).toBe(SESSION_COOKIE);
    expect(seen.body).toEqual({ displayName: "Ada" });
  });

  it("puts no user id on the wire for a profile update, so it cannot target another user", async () => {
    let requestedPath: string | null = null;
    server.use(
      http.patch(`${INTERNAL_ORIGIN}/api/profile`, ({ request }) => {
        requestedPath = new URL(request.url).pathname;
        return HttpResponse.json({});
      }),
      // If the action ever routed a profile update through the id-bearing user endpoint, this
      // would match instead and the assertion below would fail.
      http.patch(`${INTERNAL_ORIGIN}/api/user/:userId`, () => {
        requestedPath = "USER_ENDPOINT";
        return HttpResponse.json({});
      }),
    );

    const { action } = await import("./UserProfile");
    // A tampered submission naming somebody else must make no difference.
    await action({
      request: actionRequest({ intent: "updateProfile", displayName: "Ada", userId: "someone-else" }),
    });

    expect(requestedPath).toBe("/api/profile");
  });

  it("resolves the account to close from the session, ignoring any id in the submission", async () => {
    const deleted: string[] = [];
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/profile`, () => HttpResponse.json({ id: CURRENT_USER_ID })),
      http.delete(`${INTERNAL_ORIGIN}/api/user/:userId`, ({ params }) => {
        deleted.push(String(params.userId));
        return HttpResponse.json({});
      }),
    );

    const { action } = await import("./UserProfile");
    const result = await action({
      request: actionRequest({ intent: "deleteAccount", userId: "victim-user-id" }),
    });

    expect(result).toEqual({ ok: true, intent: "deleteAccount" });
    expect(deleted).toEqual([CURRENT_USER_ID]);
    expect(deleted).not.toContain("victim-user-id");
  });

  it("reports failure rather than throwing when the API rejects the update", async () => {
    server.use(http.patch(`${INTERNAL_ORIGIN}/api/profile`, () => new HttpResponse(null, { status: 401 })));

    const { action } = await import("./UserProfile");
    const result = await action({ request: actionRequest({ intent: "updateProfile", displayName: "Ada" }) });

    expect(result.ok).toBe(false);
    expect(result.intent).toBe("updateProfile");
  });
});
