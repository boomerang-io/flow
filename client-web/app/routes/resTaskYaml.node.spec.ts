// @vitest-environment node
//
// SSR-loader-in-Node harness for the streaming /res/task/:name/yaml resource route (pattern:
// resTaskrunLog.node.spec.ts). Proves the YAML relay: Accept: application/x-yaml goes upstream,
// the body streams through untouched (chunk one arrives while upstream is still open), and the
// optional ?workspace= switches to the workspace-scoped task route.
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

async function runLoader(name: string, query: string) {
  const { loader } = await import("./resTaskYaml");
  const request = new Request(`http://localhost/res/task/${name}/yaml${query}`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request, params: { name } });
}

describe("resTaskYaml loader --- Node SSR", () => {
  it("streams the admin task's YAML through with Accept forwarded, before upstream closes", async () => {
    const encoder = new TextEncoder();
    let releaseSecondChunk!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseSecondChunk = resolve;
    });
    let forwardedAccept: string | null = null;
    let forwardedCookie: string | null = null;
    let forwardedVersion: string | null = null;

    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/task/sleep`, ({ request }) => {
        forwardedAccept = request.headers.get("accept");
        forwardedCookie = request.headers.get("cookie");
        forwardedVersion = new URL(request.url).searchParams.get("version");
        const body = new ReadableStream({
          async start(controller) {
            controller.enqueue(encoder.encode("apiVersion: v1\n"));
            await gate;
            controller.enqueue(encoder.encode("kind: Task\n"));
            controller.close();
          },
        });
        return new HttpResponse(body, { headers: { "content-type": "application/x-yaml" } });
      }),
    );

    const response = await runLoader("sleep", "?version=2");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("x-yaml");
    expect(forwardedAccept).toBe("application/x-yaml");
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(forwardedVersion).toBe("2");

    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    const first = await reader.read();
    expect(decoder.decode(first.value)).toBe("apiVersion: v1\n");

    releaseSecondChunk();
    let rest = "";
    for (let next = await reader.read(); !next.done; next = await reader.read()) {
      rest += decoder.decode(next.value);
    }
    expect(rest).toBe("kind: Task\n");
  });

  it("routes to the workspace-scoped task when ?workspace= is present", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/task/sleep`, () =>
        HttpResponse.text("kind: WorkspaceTask\n", { headers: { "content-type": "application/x-yaml" } }),
      ),
    );

    const response = await runLoader("sleep", "?workspace=tyson");
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("kind: WorkspaceTask\n");
  });

  it("returns 502 when service-core is unreachable", async () => {
    server.use(http.get(`${INTERNAL_ORIGIN}/api/task/gone`, () => HttpResponse.error()));
    const response = await runLoader("gone", "");
    expect(response.status).toBe(502);
  });
});
