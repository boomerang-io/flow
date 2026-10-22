// @vitest-environment node
//
// SSR-loader-in-Node harness (pattern: src/Features/Settings/Settings.loader.node.spec.ts) for
// the streaming /res/taskrun/:id/log resource route. The load-bearing assertion is
// NON-BUFFERING: the loader's Response must yield the first upstream chunk while the upstream
// stream is still open. A buffering implementation (e.g. axios' default text/arraybuffer read,
// or an `await upstream.text()`) cannot pass that test - the first read would only resolve after
// upstream closes, and here upstream only closes AFTER the test has already consumed chunk one.
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

async function runLoader(id: string) {
  const { loader } = await import("./resTaskrunLog");
  const request = new Request(`http://localhost/res/taskrun/${id}/log`, {
    headers: { cookie: "bfs_session=abc" },
  });
  return loader({ request, params: { id } });
}

describe("resTaskrunLog loader --- Node SSR", () => {
  it("pipes upstream chunks through before the upstream stream has closed (never buffers)", async () => {
    const encoder = new TextEncoder();
    let releaseSecondChunk!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseSecondChunk = resolve;
    });
    let forwardedCookie: string | null = null;

    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/taskrun/123/log`, ({ request }) => {
        forwardedCookie = request.headers.get("cookie");
        const body = new ReadableStream({
          async start(controller) {
            controller.enqueue(encoder.encode("first chunk\n"));
            await gate; // hold the stream open until the test has seen chunk one
            controller.enqueue(encoder.encode("second chunk\n"));
            controller.close();
          },
        });
        return new HttpResponse(body, { headers: { "content-type": "text/plain; charset=utf-8" } });
      }),
    );

    const response = await runLoader("123");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/plain");
    expect(forwardedCookie).toBe("bfs_session=abc");
    expect(response.body).not.toBeNull();

    const reader = response.body!.getReader();
    const decoder = new TextDecoder();

    // Chunk one must arrive while the upstream stream is still open (the gate is unreleased).
    const first = await reader.read();
    expect(decoder.decode(first.value)).toBe("first chunk\n");

    releaseSecondChunk();
    let rest = "";
    for (let next = await reader.read(); !next.done; next = await reader.read()) {
      rest += decoder.decode(next.value);
    }
    expect(rest).toBe("second chunk\n");
  });

  it("returns 502 when service-core is unreachable", async () => {
    server.use(http.get(`${INTERNAL_ORIGIN}/api/taskrun/456/log`, () => HttpResponse.error()));
    const response = await runLoader("456");
    expect(response.status).toBe(502);
  });
});
