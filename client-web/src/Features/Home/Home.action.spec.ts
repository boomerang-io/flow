import { http, HttpResponse } from "msw";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { action } from "./Home";

function submit(fields: Record<string, string>) {
  const body = new FormData();
  Object.entries(fields).forEach(([key, value]) => body.append(key, value));
  return new Request("http://localhost/home", { method: "POST", body });
}

describe("Home --- action", () => {
  test("closing Get started saves only that setting on the caller's profile", async () => {
    let patched: unknown;
    server.use(
      http.patch(serviceUrl.getUserProfile(), async ({ request }) => {
        patched = await request.json();
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await action({ request: submit({ intent: "hide-get-started" }) });

    expect(result).toEqual({ intent: "hide-get-started" });
    expect(patched).toEqual({ settings: { isShowGetStarted: false } });
  });

  test("a failed save is reported, so the row can come back", async () => {
    server.use(http.patch(serviceUrl.getUserProfile(), () => HttpResponse.json({}, { status: 500 })));

    const result = await action({ request: submit({ intent: "hide-get-started" }) });

    // actionError returns data(payload, { status }): the payload rides on `.data`.
    expect(result).toMatchObject({ data: { intent: "hide-get-started", error: expect.anything() } });
  });
});
