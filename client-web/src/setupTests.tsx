//@ts-nocheck
import React from "react";
import { createMemoryRouter, createRoutesFromElements, Route, RouterProvider } from "react-router-dom";
import { FlagsProvider } from "flagged";
import { render as rtlRender } from "@testing-library/react";
import { QueryClient, QueryClientProvider, setLogger } from "react-query";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { AppContextProvider, WorkspaceContextProvider } from "State/context";
import {
  featureFlags as featureFlagsFixture,
  workspaces as workspacesFixture,
  profile as userFixture,
  userWorkflows as userWorkflowsFixture,
} from "ApiServer/fixtures";
import { server } from "ApiServer/msw/node";
import { resetDb } from "ApiServer/msw/db";
import "@testing-library/jest-dom/extend-expect";

// Centralised MSW lifecycle - every spec used to call src/ApiServer's `startApiServer()`/
// `server.shutdown()` itself (Mirage); MSW's Node server is process-wide (it patches the global
// fetch/http modules once), so it's started/stopped once for the whole run here instead, with
// `resetHandlers()` clearing any per-test `server.use()` override and `resetDb()` reseeding the
// in-memory store from the fixtures between tests so mutations in one test can't leak into the
// next - the same isolation guarantee `startApiServer()`'s per-test instance used to give.
beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => {
  server.resetHandlers();
  resetDb();
});
afterAll(() => server.close());

// Specs render `ui` as a bare component/tree, as an explicit <Route path=... element={...} />
// (when a param/nested route needs to be matched), or as a <>...</> of several sibling <Route>s
// (when a test needs to navigate between two real routes - e.g. a list route to a loader-backed
// detail route, mirroring how they're actually nested in AppRoutes.tsx) - build a route tree
// that works for all three: wrap anything else in a catch-all Route, otherwise hand it straight
// to the router as-is.
function buildRoutes(ui) {
  const isRouteElement = React.isValidElement(ui) && (ui.type === Route || ui.type === React.Fragment);
  return createRoutesFromElements(isRouteElement ? ui : <Route path="*" element={ui} />);
}

// v7's data router owns its history internally (no more passing a `history` instance to
// <Router>), so expose a `history`-shaped object for the handful of specs that read
// `history.location` back out after a navigation.
function routerHistory(router) {
  return {
    get location() {
      return router.state.location;
    },
  };
}

setLogger({
  log: () => {},
  warn: () => {},
  error: () => {},
});

declare global {
  namespace NodeJS {
    interface Global {
      rtlContextRouterRender: any;
      rtlRouterRender: any;
      rtlRender: any;
      rtlQueryRender: any;
    }
  }

  // Some specs call these as bare identifiers (relying on the `global.rtlX = rtlX`
  // assignment below making them ambient), others go through `global.rtlX`
  // explicitly — both need a type here.
  // eslint-disable-next-line no-var
  var rtlContextRouterRender: typeof rtlContextRouterRender;
  // eslint-disable-next-line no-var
  var rtlRouterRender: typeof rtlRouterRender;
  // eslint-disable-next-line no-var
  var rtlRender: typeof rtlRender;
  // eslint-disable-next-line no-var
  var rtlQueryRender: typeof rtlQueryRender;
}

function rtlQueryRender(ui, { queryConfig = {} } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: 0 },
      mutations: { throwOnError: true },
      ...queryConfig,
    },
  });
  return {
    ...rtlRender(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>),
  };
}

function rtlRouterRender(ui, { route = "/", ...options } = {}) {
  const router = createMemoryRouter(buildRoutes(ui), { initialEntries: [route] });
  return {
    ...rtlRender(<RouterProvider router={router} />, options),
    history: routerHistory(router),
  };
}

const defaultContextValue = {
  user: userFixture,
  // AppContext.workspaces is FlowWorkspaceSummary[] (App.tsx: sortBy(userData.teams, "name")) -
  // the fixture module holds the paginated wire response, so unwrap it to the flat array here.
  workspaces: workspacesFixture.content,
  userWorkflows: userWorkflowsFixture,
};

// Production always reaches workspace-scoped screens through WorkspaceContainer, which supplies
// this once its workspace query resolves - specs render those components directly, so supply it here.
const defaultWorkspaceValue = { workspace: workspacesFixture.content[0] };

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

function rtlContextRouterRender(
  ui,
  { contextValue = {}, workspaceValue = {}, initialState = {}, route = "/", queryConfig = {}, ...options } = {}
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: 0 },
      mutations: { throwOnError: true },
      ...queryConfig,
    },
  });
  const router = createMemoryRouter(buildRoutes(ui), { initialEntries: [route] });
  return {
    ...rtlRender(
      <FlagsProvider features={defaultFeatures}>
        <AppContextProvider value={{ ...defaultContextValue, ...contextValue }}>
          <WorkspaceContextProvider value={{ ...defaultWorkspaceValue, ...workspaceValue }}>
            <QueryClientProvider client={queryClient}>
              <RouterProvider router={router} />
            </QueryClientProvider>
          </WorkspaceContextProvider>
        </AppContextProvider>
      </FlagsProvider>,
      options
    ),
    history: routerHistory(router),
  };
}

// React's useId() counter is per-worker, so a component's generated ids depend on how many other
// trees mounted before it in the same run. Normalise them so snapshots compare on structure.
// The placeholder deliberately contains no colon, so a normalised value cannot match again and
// re-enter this serializer. The test pattern is non-global - a /g regex carries lastIndex between
// calls and would match every other time.
const REACT_GENERATED_ID = /:r[0-9a-z]+:/;
expect.addSnapshotSerializer({
  test: (value) => typeof value === "string" && REACT_GENERATED_ID.test(value),
  serialize: (value, config, indentation, depth, refs, printer) =>
    printer(String(value).replace(/:r[0-9a-z]+:/g, "[generated-id]"), config, indentation, depth, refs),
});

// Fix "react-modal: No elements were found for selector #app." error. Guarded: this setupFile
// now also runs for `@vitest-environment node` spec files (the SSR-loader-in-Node harness - see
// its module doc), which have no `document` at all.
beforeEach(() => {
  if (typeof document !== "undefined") {
    document.body.setAttribute("id", "app");
  }
});

const originalConsoleError = console.error;
console.error = (message, ...rest) => {
  if (
    typeof message === "string" &&
    !message.includes("react-modal: App element is not defined") &&
    !message.includes("MultiSelectComboBox uses getDerivedStateFromProps()")
  ) {
    originalConsoleError(message, ...rest);
  }
};

const originalConsoleWarn = console.warn;
console.warn = (message, ...rest) => {
  if (typeof message === "string" && !message.includes("Invalid date provided")) {
    originalConsoleWarn(message, ...rest);
  }
};

// RTL globals
// Open question if we want to attach these to the global or required users to import
global.rtlRender = rtlRender;
global.rtlRouterRender = rtlRouterRender;
global.rtlContextRouterRender = rtlContextRouterRender;
global.rtlQueryRender = rtlQueryRender;

const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  clear: vi.fn(),
  length: 0,
  key: vi.fn(),
  removeItem: vi.fn(),
};
const sessionStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  clear: vi.fn(),
  length: 0,
  key: vi.fn(),
  removeItem: vi.fn(),
};
global.localStorage = localStorageMock;
global.sessionStorage = sessionStorageMock;

// Dates
// Freeze the test clock so date-dependent renders (calendars, "time ago" labels, relative-date
// snapshots) are deterministic across CI runs and operator machines/timezones. Previously this
// was attempted via `vi.importMock("moment", factory)` — `importMock` takes no factory argument
// (see the vitest type defs) and its returned Promise was never awaited or used, so this never
// actually pinned anything: every date-bearing test was silently rendering against the real
// wall-clock date/time, which is why snapshots have drifted over time. `vi.setSystemTime` mocks
// the global `Date` (and therefore `moment()`) without needing `vi.useFakeTimers()`.
const DATE_TO_USE = new Date("2020-01-01T00:00:00.000Z");
vi.setSystemTime(DATE_TO_USE);
