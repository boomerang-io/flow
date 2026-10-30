import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
import { renderWithRouter } from "Utils/testing/render";
import { buildFeatureFlags, loader, ProtectedRoute } from "./App";
import { featureFlags as featureFlagsFixture } from "ApiServer/fixtures";

// Regression guard for the failure mode where a backend settings-key rename (e.g. the
// "team.*" -> "workspace.*" feature-flag keys) lands in the /features response but the
// frontend's `feature["..."]` reads are not updated in lock-step: every flag silently
// resolves to `undefined`, with no error anywhere, and features render as disabled for
// every user regardless of configuration.
describe("App --- buildFeatureFlags", () => {
  test("every flag resolves to a boolean for the /features fixture shape", () => {
    const flags = buildFeatureFlags(featureFlagsFixture.features);

    Object.entries(flags).forEach(([flagName, value]) => {
      expect(
        typeof value,
        `${flagName} resolved to ${JSON.stringify(value)} - the key it reads must exist on the /features response`
      ).toBe("boolean");
    });
  });
});

// The wrapper's ProtectedRoute rendered a v5 <Route> internally and can't be used with
// react-router-dom v6+, so App.tsx replaced all 12 admin/workspace call sites with this small
// local component. This pins down that its gating - render the guarded element when allowed,
// otherwise the same Error403 the wrapper rendered - didn't change in the swap.
describe("App --- ProtectedRoute", () => {
  test("renders the guarded element when allowed", () => {
    renderWithRouter(
      <ProtectedRoute allowed={true}>
        <div data-testid="guarded-content">Settings</div>
      </ProtectedRoute>
    );
    expect(screen.getByTestId("guarded-content")).toBeInTheDocument();
  });

  test("renders Error403 instead of the guarded element when not allowed", () => {
    renderWithRouter(
      <ProtectedRoute allowed={false}>
        <div data-testid="guarded-content">Settings</div>
      </ProtectedRoute>
    );
    expect(screen.queryByTestId("guarded-content")).not.toBeInTheDocument();
    expect(screen.getByText("Sorry mate, you are not allowed here.")).toBeInTheDocument();
    expect(
      screen.getByText("If you think you should be, contact your friendly neighborhood platform admin.")
    ).toBeInTheDocument();
  });
});

// The root bootstrap loader blocks the first paint of every route in the app, so its reads have
// to go out together. `context` and `navigation` are the exception - they are user-scoped and
// genuinely wait on the profile, and so do the starters (they are read through the user's first workspace) -
// but the feature flags were always unconditional, and awaiting the profile before firing them adds a round
// trip to every cold load.
describe("App --- root loader concurrency", () => {
  test("fires the feature flags alongside the profile fetch and the starters with context and navigation", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(serviceUrl.getUserProfile(), trace.resolver("profile", { id: "user-1", workspaces: [{ name: "payments" }] })),
      http.get(serviceUrl.getFeatureFlags(), trace.resolver("features", { features: {} })),
      http.get(
        serviceUrl.workspace.workflow.getWorkflows({ workspace: "payments" }),
        trace.resolver("templates", { content: [] }),
      ),
      http.get(serviceUrl.getContext(), trace.resolver("context", {})),
      http.get(serviceUrl.getNavigation({ query: "" }), trace.resolver("navigation", [])),
    );

    await loader({ request: new Request("http://localhost/home") });

    expect(trace.startedTogether(2)).toBe(true);
    // The user-scoped reads still wait on the profile - that dependency is real.
    expect(trace.events.indexOf("profile:end")).toBeLessThan(trace.events.indexOf("context:start"));
    expect(trace.events.indexOf("profile:end")).toBeLessThan(trace.events.indexOf("templates:start"));
  });
});

// GET /auth/config is the sign-in surface's own read; a deployment whose flags say it has none
// (security off) is never asked for it.
describe("App --- root loader sign-in config", () => {
  function serve(features: Record<string, boolean>, profileStatus = 200) {
    const trace = createRequestTrace();
    server.use(
      http.get(serviceUrl.getUserProfile(), () =>
        profileStatus === 200 ? Response.json({ id: "user-1" }) : new Response(null, { status: profileStatus }),
      ),
      http.get(serviceUrl.getFeatureFlags(), () =>
        profileStatus === 200 ? Response.json({ features }) : new Response(null, { status: profileStatus }),
      ),
      http.get(serviceUrl.getContext(), () => Response.json({})),
      http.get(serviceUrl.getNavigation({ query: "" }), () => Response.json([])),
      http.get(serviceUrl.getAuthConfig(), trace.resolver("authConfig", { mode: "oidc" })),
    );
    return trace;
  }

  test("skips the sign-in config when the flags say there is no sign-in surface", async () => {
    const trace = serve({ authentication: false });

    const data = await loader({ request: new Request("http://localhost/home") });

    expect(trace.events).not.toContain("authConfig:start");
    expect(data.authConfig).toBeNull();
  });

  test("reads the sign-in config when the flags allow sign-in", async () => {
    const trace = serve({ authentication: true });

    const data = await loader({ request: new Request("http://localhost/home") });

    expect(trace.events).toContain("authConfig:start");
    expect(data.authConfig).toEqual({ mode: "oidc" });
  });

  test("reads the sign-in config for a signed-out caller, whose flags read is refused", async () => {
    const trace = serve({}, 401);

    const data = await loader({ request: new Request("http://localhost/home") });

    expect(trace.events).toContain("authConfig:start");
    expect(data.authConfig).toEqual({ mode: "oidc" });
  });
});

// Outside a workspace the side nav points at the last one opened (the flow_last_workspace cookie),
// but only while the caller can still read it: the server builds the links from any name.
describe("App --- root loader last workspace", () => {
  function serve(workspaceStatus: number) {
    const asked = { navigation: [] as Array<string | null>, workspaceReads: 0 };
    server.use(
      http.get(serviceUrl.getUserProfile(), () => HttpResponse.json({ id: "user-1" })),
      http.get(serviceUrl.getFeatureFlags(), () => HttpResponse.json({ features: {} })),
      http.get(serviceUrl.getContext(), () => HttpResponse.json({})),
      http.get(serviceUrl.resourceWorkspace({ workspace: "payments" }), () => {
        asked.workspaceReads += 1;
        return HttpResponse.json({ name: "payments" }, { status: workspaceStatus });
      }),
      http.get(serviceUrl.getNavigation({ query: "" }), ({ request }) => {
        asked.navigation.push(new URL(request.url).searchParams.get("workspace"));
        return HttpResponse.json([]);
      }),
    );
    return asked;
  }
  const request = (path: string) =>
    new Request(`http://localhost/apps/flow${path}`, { headers: { Cookie: "flow_last_workspace=payments" } });

  test("scopes Home's nav to the remembered workspace the caller can read", async () => {
    const asked = serve(200);
    await loader({ request: request("/home") });
    expect(asked.navigation).toEqual(["payments"]);
  });

  test("leaves the nav unscoped when the remembered workspace can no longer be read", async () => {
    const asked = serve(400);
    await loader({ request: request("/home") });
    expect(asked.navigation).toEqual([null]);
  });

  test("uses the workspace in the path, without reading the remembered one", async () => {
    const asked = serve(200);
    await loader({ request: request("/billing/workflows") });
    expect(asked.navigation).toEqual(["billing"]);
    expect(asked.workspaceReads).toBe(0);
  });
});
