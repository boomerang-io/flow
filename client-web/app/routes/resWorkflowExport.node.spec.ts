// @vitest-environment node
//
// SSR-loader-in-Node harness for the streaming /res/workspace/:workspace/workflow/:workflow/export
// resource route (pattern: resTaskrunLog.node.spec.ts). The load-bearing assertion is
// NON-BUFFERING: exported workflows can be large, so the loader's Response must yield the first
// upstream chunk while the upstream stream is still open - a buffering implementation cannot
// pass, because upstream only closes AFTER the test has already consumed chunk one.
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

async function runLoader(workspace: string, workflow: string) {
  const { loader } = await import("./resWorkflowExport");
  const request = new Request(`http://localhost/res/workspace/${workspace}/workflow/${workflow}/export`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request, params: { workspace, workflow } });
}

describe("resWorkflowExport loader --- Node SSR", () => {
  it("pipes upstream chunks through before the upstream stream has closed (never buffers)", async () => {
    const encoder = new TextEncoder();
    let releaseSecondChunk!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseSecondChunk = resolve;
    });
    let forwardedCookie: string | null = null;

    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/workflow/my-flow/export`, ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        const body = new ReadableStream({
          async start(controller) {
            controller.enqueue(encoder.encode('{"name":"my-flow",'));
            await gate; // hold the stream open until the test has seen chunk one
            controller.enqueue(encoder.encode('"tasks":[]}'));
            controller.close();
          },
        });
        return new HttpResponse(body, {
          headers: {
            "content-type": "application/json",
            "content-disposition": 'attachment; filename="my-flow.json"',
          },
        });
      }),
    );

    const response = await runLoader("tyson", "my-flow");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    // The filename survives: upstream's content-disposition passes through untouched.
    expect(response.headers.get("content-disposition")).toBe('attachment; filename="my-flow.json"');
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(response.body).not.toBeNull();

    const reader = response.body!.getReader();
    const decoder = new TextDecoder();

    // Chunk one must arrive while the upstream stream is still open (the gate is unreleased).
    const first = await reader.read();
    expect(decoder.decode(first.value)).toBe('{"name":"my-flow",');

    releaseSecondChunk();
    let rest = "";
    for (let next = await reader.read(); !next.done; next = await reader.read()) {
      rest += decoder.decode(next.value);
    }
    expect(rest).toBe('"tasks":[]}');
  });

  it("returns 502 when service-core is unreachable", async () => {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/workspace/tyson/workflow/gone/export`, () => HttpResponse.error()),
    );
    const response = await runLoader("tyson", "gone");
    expect(response.status).toBe(502);
  });
});
