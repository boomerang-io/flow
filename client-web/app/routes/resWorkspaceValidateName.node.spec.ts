// @vitest-environment node
//
// SSR-loader-in-Node harness (pattern: src/Features/Settings/Settings.loader.node.spec.ts):
// proves the /res/workspace/validate-name resource route's loader translates the upstream
// validate-name POST into its stable `{ available: boolean }` contract in a plain Node process -
// the environment it actually runs in under ssr:true.
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
  const { loader } = await import("./resWorkspaceValidateName");
  const request = new Request(`http://localhost/res/workspace/validate-name${query}`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request });
}

describe("resWorkspaceValidateName loader --- Node SSR", () => {
  it("returns available:true when upstream accepts the name, forwarding the inbound cookie", async () => {
    let forwardedCookie: string | null = null;
    let upstreamBody: unknown = null;
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/workspace/validate-name`, async ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        upstreamBody = await request.json();
        return HttpResponse.json({});
      }),
    );

    const response = await runLoader("?name=fresh-workspace");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ available: true });
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(upstreamBody).toEqual({ name: "fresh-workspace" });
  });

  it("folds an upstream collision (422) into available:false", async () => {
    server.use(
      http.post(`${INTERNAL_ORIGIN}/api/workspace/validate-name`, () =>
        HttpResponse.json({ errors: ["Name is already taken"] }, { status: 422 }),
      ),
    );

    const response = await runLoader("?name=tyson-workspace");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ available: false });
  });

  it("rejects a missing name with 400/available:false without calling upstream", async () => {
    const response = await runLoader("");
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ available: false });
  });
});
