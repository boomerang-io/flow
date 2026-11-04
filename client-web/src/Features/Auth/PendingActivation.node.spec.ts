// @vitest-environment node
//
// The OpenID Connect sign-in on an install with no admin yet: the exchange asks for the activation
// code (423), the callback holds the verified sign-in in an httpOnly cookie, the root loader then
// shows the activation screen instead of the signed-out page, and the activation submit finishes
// the sign-in with the code. Behind a proxy the submit stays the PUT to /api/v2/activate.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import {
  clearPendingActivation,
  PENDING_ACTIVATION_COOKIE,
  readPendingActivation,
  writePendingActivation,
} from "./pendingActivation";

const INTERNAL_ORIGIN = "http://core-service.internal";
const APP_ORIGIN = "http://localhost:3000";

beforeEach(() => {
  vi.stubEnv("CORE_SERVICE_INTERNAL_ORIGIN", INTERNAL_ORIGIN);
  vi.resetModules();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

function heldCookie(idToken = "a.b.c", nonce = "n-1") {
  return writePendingActivation(idToken, nonce).split(";")[0];
}

function activateRequest(cookie?: string) {
  return new Request(`${APP_ORIGIN}/apps/flow/res/activate`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(cookie ? { cookie } : {}) },
    body: JSON.stringify({ otc: "the-code" }),
  });
}

describe("pending activation cookie", () => {
  it("holds a verified sign-in out of the browser's reach and reads it back", () => {
    const set = writePendingActivation("a.b.c", "n 1&2");
    expect(set).toContain(`${PENDING_ACTIVATION_COOKIE}=`);
    expect(set).toMatch(/HttpOnly/);
    expect(set).toMatch(/Secure/);
    expect(set).toMatch(/Max-Age=300/);

    const request = new Request(APP_ORIGIN, { headers: { cookie: `other=1; ${set.split(";")[0]}` } });
    expect(readPendingActivation(request)).toEqual({ idToken: "a.b.c", nonce: "n 1&2" });
  });

  it("reads nothing from a request without it, and clears with an expired cookie", () => {
    expect(readPendingActivation(new Request(APP_ORIGIN))).toBeNull();
    expect(clearPendingActivation()).toMatch(/Expires=Thu, 01 Jan 1970/);
  });
});

describe("activation submit --- Node SSR", () => {
  it("finishes a held OpenID Connect sign-in with the code and relays the session", async () => {
    let body: unknown = null;
    server.use(
      http.post(`${INTERNAL_ORIGIN}/internal/v1/auth/exchange`, async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 200, headers: { "Set-Cookie": "flow_session=bfs_1; Path=/; HttpOnly" } });
      }),
    );

    const { action } = await import("../../../app/routes/resActivate");
    const result: any = await action({ request: activateRequest(heldCookie()) });

    expect(body).toEqual({ idToken: "a.b.c", nonce: "n-1", otc: "the-code" });
    expect(result.data).toEqual({ ok: true });
    const cookies = new Headers(result.init.headers).getSetCookie();
    expect(cookies[0]).toContain("flow_session=bfs_1");
    expect(cookies[1]).toContain(`${PENDING_ACTIVATION_COOKIE}=;`);
  });

  it("returns a wrong code as data and keeps the held sign-in", async () => {
    server.use(
      http.post(`${INTERNAL_ORIGIN}/internal/v1/auth/exchange`, () =>
        HttpResponse.json({ reason: "AUTH_ACTIVATION_CODE_INVALID" }, { status: 400 }),
      ),
    );

    const { action } = await import("../../../app/routes/resActivate");
    const result: any = await action({ request: activateRequest(heldCookie()) });

    expect(result).toEqual({ ok: false, status: 400, data: { reason: "AUTH_ACTIVATION_CODE_INVALID" } });
  });

  it("behind a proxy submits the code with the PUT to the activation route", async () => {
    let body: unknown = null;
    server.use(
      http.put(`${INTERNAL_ORIGIN}/api/activate`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({});
      }),
    );

    const { action } = await import("../../../app/routes/resActivate");
    const result: any = await action({ request: activateRequest() });

    expect(body).toEqual({ otc: "the-code" });
    expect(result).toEqual({ ok: true });
  });
});

describe("activation submit behind a proxy --- Node SSR", () => {
  it("returns the structured wrong-code error as data", async () => {
    const body = {
      code: 1007,
      reason: "AUTH_ACTIVATION_CODE_INVALID",
      message: "The activation code is not correct.",
      status: "400 BAD_REQUEST",
    };
    server.use(http.put(`${INTERNAL_ORIGIN}/api/activate`, () => HttpResponse.json(body, { status: 400 })));

    const { action } = await import("../../../app/routes/resActivate");
    const result: any = await action({ request: activateRequest() });

    expect(result).toEqual({ ok: false, status: 400, data: body });
  });
});

describe("root loader --- Node SSR", () => {
  function core(profileStatus: number) {
    server.use(
      http.get(`${INTERNAL_ORIGIN}/api/features`, () => HttpResponse.json({ features: {} })),
      http.get(`${INTERNAL_ORIGIN}/internal/v1/auth/config`, () => HttpResponse.json({ mode: "oidc" })),
      http.get(`${INTERNAL_ORIGIN}/api/profile`, () => new HttpResponse(null, { status: profileStatus })),
    );
  }

  it("shows the activation screen to a held sign-in, and the signed-out page to anyone else", async () => {
    core(401);
    const { loader } = await import("../App/App");

    const held: any = await loader({
      request: new Request(`${APP_ORIGIN}/apps/flow/`, { headers: { cookie: heldCookie() } }),
    });
    const stranger: any = await loader({ request: new Request(`${APP_ORIGIN}/apps/flow/`) });

    expect(held.status).toBe("activationRequired");
    expect(stranger.status).toBe("unauthorized");
  });
});
