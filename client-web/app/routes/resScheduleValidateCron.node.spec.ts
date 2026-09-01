// @vitest-environment node
//
// SSR-loader-in-Node harness (pattern: resWorkspaceValidateName.node.spec.ts): proves the
// /res/workspace/:workspace/schedule/validate-cron resource route's loader relays the upstream
// cron-validation GET as its stable `{ valid: boolean, message?: string }` contract in a plain
// Node process - the environment it actually runs in under ssr:true.
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

async function runLoader(workspace: string, query: string) {
  const { loader } = await import("./resScheduleValidateCron");
  const request = new Request(
    `http://localhost/res/workspace/${workspace}/schedule/validate-cron${query}`,
    { headers: { cookie: "bfs_session=abc" } },
  );
  return loader({ request, params: { workspace } });
}

describe("resScheduleValidateCron loader --- Node SSR", () => {
  it("relays a valid upstream verdict, forwarding the inbound cookie and cron expression", async () => {
    let forwardedCookie: string | null = null;
    let forwardedCron: string | null = null;
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/schedule/validate-cron`, ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        forwardedCron = new URL(request.url).searchParams.get("cron");
        return HttpResponse.json({ valid: true, cron: "0 0 * * MON" });
      }),
    );

    const response = await runLoader("tyson", `?cron=${encodeURIComponent("0 0 * * MON")}`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ valid: true });
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(forwardedCron).toBe("0 0 * * MON");
  });

  it("relays an invalid verdict with the upstream message", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/schedule/validate-cron`, () =>
        HttpResponse.json({ valid: false, message: "Failed to parse cron expression" }),
      ),
    );

    const response = await runLoader("tyson", "?cron=nonsense");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ valid: false, message: "Failed to parse cron expression" });
  });

  it("folds an upstream failure (5xx/unreachable) into an invalid verdict with a retry message", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/schedule/validate-cron`, () =>
        HttpResponse.json({}, { status: 503 }),
      ),
    );

    const response = await runLoader("tyson", "?cron=0+0+*+*+*");
    expect(response.status).toBe(200);
    const body = (await response.json()) as { valid: boolean; message?: string };
    expect(body.valid).toBe(false);
    expect(body.message).toMatch(/try again/i);
  });

  it("rejects a missing cron with 400/valid:false without calling upstream", async () => {
    const response = await runLoader("tyson", "");
    expect(response.status).toBe(400);
    expect((await response.json()).valid).toBe(false);
  });
});
