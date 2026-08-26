import { http } from "msw";
import { screen } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { createRequestTrace } from "ApiServer/msw/requestTrace";
import { serviceUrl } from "Config/servicesConfig";
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
    global.rtlRouterRender(
      <ProtectedRoute allowed={true}>
        <div data-testid="guarded-content">Settings</div>
      </ProtectedRoute>
    );
    expect(screen.getByTestId("guarded-content")).toBeInTheDocument();
  });

  test("renders Error403 instead of the guarded element when not allowed", () => {
    global.rtlRouterRender(
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
// genuinely wait on the profile - but feature flags and workflow templates were always
// unconditional, and awaiting the profile before firing them adds a round trip to every cold load.
describe("App --- root loader concurrency", () => {
  test("fires the feature flags and workflow templates alongside the profile fetch", async () => {
    const trace = createRequestTrace();
    server.use(
      http.get(serviceUrl.getUserProfile(), trace.resolver("profile", { id: "user-1" })),
      http.get(serviceUrl.getFeatureFlags(), trace.resolver("features", { features: {} })),
      http.get(serviceUrl.template.getWorkflowTemplates(), trace.resolver("templates", { content: [] })),
      http.get(serviceUrl.getContext(), trace.resolver("context", {})),
      http.get(serviceUrl.getNavigation({ query: "" }), trace.resolver("navigation", [])),
    );

    await loader({ request: new Request("http://localhost/home") });

    expect(trace.startedTogether(3)).toBe(true);
    // The user-scoped pair still waits on the profile - that dependency is real.
    expect(trace.events.indexOf("profile:end")).toBeLessThan(trace.events.indexOf("context:start"));
  });
});
