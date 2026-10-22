// @vitest-environment node
//
// Runs the /admin/users/:userId action in a real Node environment - see
// Features/UserProfile/UserProfile.action.node.spec.ts and
// Features/Settings/Settings.loader.node.spec.ts for the harness rationale. The property pinned
// here is that the user being written to comes from the ROUTE PARAM, never from the submitted
// form, and that the caller's session cookie is forwarded on the outbound call (a bare
// axios/resolver call from an action would carry no credentials at all in Node).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { isActionError } from "Utils/actionResult";

const INTERNAL_ORIGIN = "http://core-service.internal";
const SESSION_COOKIE = "bfs_session=abc123";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

function actionRequest(fields: Record<string, string>) {
  return new Request("http://localhost/admin/users/user-in-url", {
    method: "POST",
    headers: { cookie: SESSION_COOKIE, "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields),
  });
}

/** Captures every PATCH /user/:userId the action makes. */
function capturePatches() {
  const calls: Array<{ userId: string; body: any; cookie: string | null }> = [];
  server.use(
    http.patch(`${INTERNAL_ORIGIN}/api/user/:userId`, async ({ request, params }) => {
      calls.push({
        userId: String(params.userId),
        body: await request.json(),
        cookie: request.headers.get("cookie"),
      });
      return HttpResponse.json({});
    }),
  );
  return calls;
}

describe("UserDetailed action --- Node SSR", () => {
  it("changes the role of the user named in the URL, forwarding the caller's cookie", async () => {
    expect(typeof window).toBe("undefined");
    const calls = capturePatches();

    const { action } = await import("./UserDetailed");
    const result = await action({
      params: { userId: "user-in-url" },
      request: actionRequest({ intent: "changeRole", type: "admin" }),
    });

    expect(result).toEqual({ intent: "changeRole" });
    expect(calls).toHaveLength(1);
    expect(calls[0].userId).toBe("user-in-url");
    expect(calls[0].body).toEqual({ type: "admin" });
    expect(calls[0].cookie).toBe(SESSION_COOKIE);
  });

  it("ignores a userId smuggled into the submission - the route param wins", async () => {
    const calls = capturePatches();

    const { action } = await import("./UserDetailed");
    await action({
      params: { userId: "user-in-url" },
      request: actionRequest({ intent: "changeRole", type: "admin", userId: "victim-user-id" }),
    });

    expect(calls[0].userId).toBe("user-in-url");
    expect(calls[0].userId).not.toBe("victim-user-id");
  });

  it("saves labels for the user named in the URL", async () => {
    const calls = capturePatches();

    const { action } = await import("./UserDetailed");
    const result = await action({
      params: { userId: "user-in-url" },
      request: actionRequest({ intent: "saveLabels", labels: JSON.stringify({ team: "platform" }) }),
    });

    expect(result).toEqual({ intent: "saveLabels" });
    expect(calls[0].body).toEqual({ labels: { team: "platform" } });
  });

  it("reports failure rather than throwing when the API rejects the write", async () => {
    server.use(http.patch(`${INTERNAL_ORIGIN}/api/user/:userId`, () => new HttpResponse(null, { status: 403 })));

    const { action } = await import("./UserDetailed");
    // Calling `action` directly (rather than through a router) surfaces the raw
    // DataWithResponseInit wrapper actionError() returns for a failure - the router itself
    // unwraps it into fetcher.data in real use.
    const result = (await action({
      params: { userId: "user-in-url" },
      request: actionRequest({ intent: "changeRole", type: "admin" }),
    })) as unknown as { data: { intent: string; error: unknown } };

    expect(isActionError(result.data)).toBe(true);
    expect(result.data.intent).toBe("changeRole");
    expect(result.data.error).toBeDefined();
  });
});
