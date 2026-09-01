import React from "react";
import {
  Route,
  createRoutesFromElements,
  createRoutesStub,
  useLocation,
  type ActionFunction,
  type LoaderFunction,
  type Location,
  type RouteObject,
} from "react-router-dom";
import { render as rtlRender, type RenderOptions } from "@testing-library/react";
import { FlagsProvider } from "flagged";
import { QueryClient, QueryClientProvider, type MutationObserverOptions } from "react-query";
import { AppContextProvider, WorkspaceContextProvider } from "State/context";
import {
  featureFlags as featureFlagsFixture,
  workspaces as workspacesFixture,
  profile as userFixture,
} from "ApiServer/fixtures";

// Replaces setupTests.tsx's hand-rolled `buildRoutes` + `createMemoryRouter` (C7,
// specifications/framework-review-wave.md). React Router 7.18 ships `createRoutesStub` for
// exactly this - a real (mocked) data router driven off a route tree, rather than the plain
// <RouterProvider> wrapper the old helpers used, so `useLoaderData`/`useFetcher`/`useActionData`
// work without hand-wiring a router by hand.
//
// `createRoutesStub` takes `StubRouteObject[]` - the same shape as `RouteObject[]` except
// `element`/`errorElement` are replaced by `Component`/`ErrorBoundary` (component types, not
// JSX). Specs already build their route tree as JSX (`<Route path=... loader=... element=.../>`,
// sometimes a `<>...</>` of siblings for cross-route navigation, or nested `<Route>` children for
// a tabbed layout route) - `createRoutesFromElements` (still exported, unchanged from v6)
// converts that JSX into `RouteObject[]`, and `toStubRoutes` below re-maps each `element` to a
// `Component` that just returns it. That keeps every existing spec's route-tree JSX working
// unchanged - only the render call itself moves off the `global.rtlXRender` ambients.
type StubRoutes = Parameters<typeof createRoutesStub>[0];

// A handful of specs (Insights.spec.tsx, Activity.spec.tsx, Actions.spec.tsx,
// Configure.spec.tsx) destructure `{ history }` off the render return value and read
// `history.location` after a navigation - the same shape the old `routerHistory(router)` shim in
// setupTests.tsx exposed off the `createMemoryRouter` instance it built by hand.
// `createRoutesStub` doesn't hand back its internal router (it returns a component, not a
// router instance), so there's no router object to read `.state.location` off any more. Instead,
// every route's `Component` is wrapped with an invisible sibling that calls `useLocation()` -
// which works from anywhere inside the router, matched route or not - and mutates a closure
// variable on every navigation; `history.location` is a getter over that variable, so it stays
// live without the caller re-rendering.
function createHistoryHandle() {
  let current: Location | undefined;
  function HistoryProbe() {
    current = useLocation();
    return null;
  }
  return {
    HistoryProbe,
    history: {
      get location() {
        if (!current) {
          throw new Error("history.location read before the router committed its first render");
        }
        return current;
      },
    },
  };
}

function toStubRoutes(routes: RouteObject[], HistoryProbe: React.ComponentType): StubRoutes {
  return routes.map((route) => {
    const { element, errorElement, children, ...rest } = route as RouteObject & { element?: React.ReactNode };
    return {
      ...rest,
      ...(element !== undefined
        ? {
            Component: () => (
              <>
                <HistoryProbe />
                {element}
              </>
            ),
          }
        : {}),
      ...(children ? { children: toStubRoutes(children, HistoryProbe) } : {}),
    };
  }) as StubRoutes;
}

function isRouteTree(ui: React.ReactElement): boolean {
  return ui.type === Route || ui.type === React.Fragment;
}

export interface RenderRouteOptions {
  /** Path pattern for the single implicit route wrapping a bare `ui` element. Defaults to "*". */
  path?: string;
  /** Convenience for `initialEntries: [route]` - matches the single-location case nearly every spec needs. */
  route?: string;
  initialEntries?: string[];
  initialIndex?: number;
  loader?: LoaderFunction;
  action?: ActionFunction;
  /** A full route tree (as `RouteObject[]`) for cases `path`/`loader`/`action` can't express - e.g.
   * Users.spec.tsx's two sibling top-level routes, or WorkspaceDetailed.spec.tsx's tabbed children. */
  routes?: RouteObject[];
}

/** Builds the StubRouteObject[] a spec's `ui` implies: an explicit route tree (`options.routes`),
 * `ui` itself already being a <Route>/<>...</> tree, or a bare element wrapped in one route. */
function buildStubRoutes(ui: React.ReactElement, options: RenderRouteOptions, HistoryProbe: React.ComponentType) {
  if (options.routes) return toStubRoutes(options.routes, HistoryProbe);
  if (isRouteTree(ui)) return toStubRoutes(createRoutesFromElements(ui), HistoryProbe);
  return toStubRoutes(
    createRoutesFromElements(
      <Route path={options.path ?? "*"} loader={options.loader} action={options.action} element={ui} />
    ),
    HistoryProbe
  );
}

function initialEntriesFor(options: RenderRouteOptions): string[] {
  if (options.initialEntries) return options.initialEntries;
  if (options.route) return [options.route];
  return ["/"];
}

/** Replaces `global.rtlRouterRender` - RTL render inside a real (stubbed) data router, no app
 * context/providers. For specs whose component only needs router plumbing (`<Link>`, `useFetcher`,
 * a route's `loader`/`action`). */
export function renderWithRouter(
  ui: React.ReactElement,
  options: RenderRouteOptions & Omit<RenderOptions, "wrapper"> = {}
) {
  const { path, route, initialEntries, initialIndex, loader, action, routes, ...renderOptions } = options;
  const { HistoryProbe, history } = createHistoryHandle();
  const Stub = createRoutesStub(buildStubRoutes(ui, { path, route, initialEntries, loader, action, routes }, HistoryProbe));
  return {
    ...rtlRender(
      <Stub initialEntries={initialEntriesFor({ route, initialEntries })} initialIndex={initialIndex} />,
      renderOptions
    ),
    history,
  };
}

const feature = featureFlagsFixture.features;

const defaultFeatures = {
  ActivityEnabled: feature["activity"],
  EditVerifiedTasksEnabled: feature["enable.verified.tasks.edit"],
  GlobalParametersEnabled: feature["global.parameters"],
  InsightsEnabled: feature["insights"],
  WorkspaceManagementEnabled: feature["workspace.management"],
  WorkspaceParametersEnabled: feature["workspace.parameters"],
  WorkspaceTasksEnabled: feature["workspace.tasks"],
  UserManagementEnabled: feature["user.management"],
  WorkspaceQuotasEnabled: feature["workspace.quotas"],
  WorkflowTokensEnabled: feature["workflow.tokens"],
  WorkflowTriggersEnabled: feature["workflow.triggers"],
};

// `userWorkflows` was carried here in the old setupTests.tsx harness too, importing a fixture
// export ("ApiServer/fixtures" has no `userWorkflows` member - Did you mean 'workflows'?) that
// has never existed; the file was `//@ts-nocheck`d so the bad import silently resolved to
// `undefined` at runtime instead of failing the build. `AppContext` (State/context/index.tsx)
// has no `userWorkflows` field either, so nothing downstream ever read it - dropped rather than
// carried forward.
const defaultContextValue = {
  user: userFixture,
  // AppContext.workspaces is FlowWorkspaceSummary[] (App.tsx: sortBy(userData.teams, "name")) -
  // the fixture module holds the paginated wire response, so unwrap it to the flat array here.
  workspaces: workspacesFixture.content,
};

// Production always reaches workspace-scoped screens through WorkspaceContainer, which supplies
// this once its workspace query resolves - specs render those components directly, so supply it here.
const defaultWorkspaceValue = { workspace: workspacesFixture.content[0] };

export interface RenderContextOptions extends RenderRouteOptions, Omit<RenderOptions, "wrapper"> {
  // Loosely typed on purpose: callers pass fixture slices (e.g. `profile.teams`) whose literal
  // shape doesn't line up 1:1 with `AppContext.workspaces`/`.user` (untyped ApiServer/fixtures
  // modules predate the webapp/API type alignment noted in CLAUDE.md) - same laxity the old
  // `rtlContextRouterRender`'s untyped/`//@ts-nocheck`'d `contextValue` param had.
  contextValue?: Partial<Record<keyof typeof defaultContextValue, unknown>>;
}

/** Replaces `global.rtlContextRouterRender` - the router render above, plus the app-wide
 * providers a workspace-scoped feature needs: feature flags, AppContext, WorkspaceContext, and a
 * fresh react-query v3 QueryClient per render (retries off, mutation errors thrown so a spec's
 * `await expect(...).rejects` sees them). */
export function renderWithContext(ui: React.ReactElement, options: RenderContextOptions = {}) {
  const { contextValue, path, route, initialEntries, initialIndex, loader, action, routes, ...renderOptions } =
    options;
  const queryClient = new QueryClient({
    // `throwOnError` (not a real react-query v3 `MutationObserverOptions` key - the type only
    // has `useErrorBoundary`) is carried over unchanged from the old harness's identical
    // `rtlContextRouterRender`, itself `//@ts-nocheck`d, so this was already a silent no-op
    // there; kept as-is (cast) rather than "fixed" to `useErrorBoundary`, which has different
    // render-time-throw semantics and is out of this migration's scope to change.
    defaultOptions: {
      queries: { retry: 0 },
      mutations: { throwOnError: true } as MutationObserverOptions<unknown, unknown, unknown, unknown>,
    },
  });
  const { HistoryProbe, history } = createHistoryHandle();
  const Stub = createRoutesStub(buildStubRoutes(ui, { path, route, initialEntries, loader, action, routes }, HistoryProbe));
  return {
    ...rtlRender(
      <FlagsProvider features={defaultFeatures}>
        {/* `defaultContextValue`/`defaultWorkspaceValue` only ever supplied a subset of `AppContext`/
        `WorkspaceContext` (both types local to State/context/index.tsx and not exported) - true in
        the old `//@ts-nocheck`d harness too. Cast rather than widen the fixtures or export+narrow
        the app's own context types, which is out of scope here. */}
        <AppContextProvider
          value={{ ...defaultContextValue, ...contextValue } as unknown as Parameters<typeof AppContextProvider>[0]["value"]}
        >
          <WorkspaceContextProvider
            value={defaultWorkspaceValue as unknown as Parameters<typeof WorkspaceContextProvider>[0]["value"]}
          >
            <QueryClientProvider client={queryClient}>
              <Stub initialEntries={initialEntriesFor({ route, initialEntries })} initialIndex={initialIndex} />
            </QueryClientProvider>
          </WorkspaceContextProvider>
        </AppContextProvider>
      </FlagsProvider>,
      renderOptions
    ),
    history,
  };
}
