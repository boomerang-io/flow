// @vitest-environment node
//
// SSR-loader-in-Node harness (pattern: resWorkspaceValidateName.node.spec.ts): proves the
// /res/users resource route's loader relays the upstream user-query GET as its stable
// `{ ok: true, users } | { ok: false }` contract in a plain Node process - the environment it
// actually runs in under ssr:true.
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";

const INTERNAL_ORIGIN = "http://core-service.internal";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

async function runLoader(query: string) {
  const { loader } = await import("./resUsers");
  const request = new Request(`http://localhost/res/users${query}`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request });
}

describe("resUsers loader --- Node SSR", () => {
  it("relays the upstream user page, forwarding the inbound cookie and the query string", async () => {
    let forwardedCookie: string | null = null;
    let forwardedSearch: string | null = null;
    const page = { content: [{ id: "u1", email: "one@example.com" }], number: 0, size: 1, totalElements: 1 };
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/user/query`, ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        forwardedSearch = new URL(request.url).search;
        return HttpResponse.json(page);
      }),
    );

    const response = await runLoader("?query=one");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, users: page });
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(forwardedSearch).toBe("?query=one");
  });

  it("folds an upstream failure into { ok: false } with a 200 status", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/user/query`, () => HttpResponse.json({}, { status: 500 })),
    );

    const response = await runLoader("");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: false });
  });
});
