//@ts-nocheck
import { Router } from "react-router-dom";
import { FlagsProvider } from "flagged";
import { createMemoryHistory } from "history";
import { render as rtlRender } from "@testing-library/react";
import { QueryClient, QueryClientProvider, setLogger } from "react-query";
import { vi } from "vitest";
import { AppContextProvider } from "State/context";
import {
  featureFlags as featureFlagsFixture,
  workspaces as workspacesFixture,
  profile as userFixture,
  userWorkflows as userWorkflowsFixture,
} from "ApiServer/fixtures";
import "@testing-library/jest-dom/extend-expect";

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

function rtlRouterRender(
  ui,
  { route = "/", history = createMemoryHistory({ initialEntries: [route] }), ...options } = {}
) {
  return {
    ...rtlRender(<Router history={history}>{ui}</Router>, options),
    history,
  };
}

const defaultContextValue = {
  user: userFixture,
  // AppContext.workspaces is FlowWorkspaceSummary[] (App.tsx: sortBy(userData.teams, "name")) -
  // the fixture module holds the paginated wire response, so unwrap it to the flat array here.
  workspaces: workspacesFixture.content,
  userWorkflows: userWorkflowsFixture,
};

const feature = featureFlagsFixture.features;

const defaultFeatures = {
  ActivityEnabled: feature["activity"],
  EditVerifiedTasksEnabled: feature["enable.verified.tasks.edit"],
  GlobalParametersEnabled: feature["global.parameters"],
  InsightsEnabled: feature["insights"],
  WorkspaceManagementEnabled: feature["team.management"],
  WorkspaceParametersEnabled: feature["team.parameters"],
  WorkspaceTasksEnabled: feature["team.tasks"],
  UserManagementEnabled: feature["user.management"],
  WorkspaceQuotasEnabled: feature["team.quotas"],
  WorkflowTokensEnabled: feature["workflow.tokens"],
  WorkflowTriggersEnabled: feature["workflow.triggers"],
};

function rtlContextRouterRender(
  ui,
  {
    contextValue = {},
    initialState = {},
    route = "/",
    queryConfig = {},
    history = createMemoryHistory({ initialEntries: [route] }),
    ...options
  } = {}
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: 0 },
      mutations: { throwOnError: true },
      ...queryConfig,
    },
  });
  return {
    ...rtlRender(
      <FlagsProvider features={defaultFeatures}>
        <AppContextProvider value={{ ...defaultContextValue, ...contextValue }}>
          <QueryClientProvider client={queryClient}>
            <Router history={history}>{ui}</Router>
          </QueryClientProvider>
        </AppContextProvider>
      </FlagsProvider>,
      options
    ),
    history,
  };
}

// Fix "react-modal: No elements were found for selector #app." error
beforeEach(() => {
  document.body.setAttribute("id", "app");
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
