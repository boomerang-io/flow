// @vitest-environment node
//
// SSR-loader-in-Node harness (pattern: app/routes/resWorkspaceValidateName.node.spec.ts):
// proves the workspace layout route's loader resolves the `:workspace` record server-side via
// serverFetch - forwarding the inbound session cookie - in a plain Node process, the environment
// it actually runs in under ssr:true. This loader replaces WorkspaceContainer's browser-side
// react-query fetch (the BFF violation the wave-2 slice closes).
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

async function runLoader(workspace: string) {
  const { loader } = await import("./workspaceLayout");
  const request = new Request(`http://localhost/${workspace}/workflows`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request, params: { workspace } });
}

describe("workspaceLayout loader --- Node SSR", () => {
  it("resolves the workspace record, forwarding the inbound cookie", async () => {
    let forwardedCookie: string | null = null;
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson-workspace`, ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        return HttpResponse.json({ id: "ws-1", name: "tyson-workspace", displayName: "Tyson Workspace" });
      }),
    );

    const data = await runLoader("tyson-workspace");
    expect(data.status).toBe("ok");
    expect(data.status === "ok" && data.workspace.name).toBe("tyson-workspace");
    expect(forwardedCookie).toBe("bfs_session=abc");
  });

  it("maps an unknown workspace (404) to notFound", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/nope`, () =>
        HttpResponse.json({ errors: ["Workspace not found"] }, { status: 404 }),
      ),
    );

    expect((await runLoader("nope")).status).toBe("notFound");
  });

  it("maps the real backend's invalid-ref shape (400 TEAM_INVALID_REF) to notFound", async () => {
    // service-core's WorkspaceService.get throws TEAM_INVALID_REF for an unknown slug, which
    // RestErrorResponse serialises as 400 BAD_REQUEST - not 404 (the MSW handler's shape).
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/nope`, () =>
        HttpResponse.json({ code: 1101, reason: "TEAM_INVALID_REF", status: "400 BAD_REQUEST" }, { status: 400 }),
      ),
    );

    expect((await runLoader("nope")).status).toBe("notFound");
  });

  it("maps an upstream failure (500) to error, never a blank/null result", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson-workspace`, () => HttpResponse.json({}, { status: 500 })),
    );

    expect((await runLoader("tyson-workspace")).status).toBe("error");
  });
});
