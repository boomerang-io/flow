//@ts-nocheck
import {
  act,
  cleanup,
  configure as configureTestingLibrary,
  getConfig as getTestingLibraryConfig,
} from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
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
afterEach(async () => {
  await unmountAndDrainReactModalPortals();
  server.resetHandlers();
  resetDb();
});
afterAll(() => server.close());

// react-modal appends its OWN container div to `document.body` - not into the container RTL
// renders into - and stamps it with the `portalClassName`, which for
// @boomerang-io/carbon-addons-boomerang-react's Modal is `cds--bmrg-modal-portal`. RTL's
// `cleanup()` only removes its own container, so that div is outside everything RTL tidies up.
//
// react-modal does not remove it synchronously either. That Modal sets `closeTimeoutMS: 240`, so
// `componentWillUnmount` takes the deferred branch - `setTimeout(this.removePortal, closesAt -
// now)` - and keeps no handle to cancel it (react-modal/lib/components/Modal.js:159). Any spec
// that ends with a modal open (several do; that IS the state under test) therefore leaves both a
// live 240ms timer and a stranded portal node behind, and the timer fires either during the NEXT
// test or after the environment is gone.
//
// The observed failure is the second case: when the leaking spec is the LAST in its file, vitest
// tears the jsdom environment down before the timer fires and `removePortal` throws
// `ReferenceError: document is not defined` at Modal.js:249. Vitest reports that as an unhandled
// error and exits non-zero with all 230 tests green - a gate failure with nothing red to point
// at (seen twice in 11 full-suite runs, originating in RunHeader.spec.tsx). The cross-test case
// has not been caught in the act, but the same stale `removePortal`/`afterClose()` pair mutates
// `document.body` either way, so draining closes both.
//
// Unmount here rather than leaving it to RTL's auto-cleanup: vitest runs `afterEach` hooks in
// reverse registration order, and RTL registers its cleanup when it is imported above, so its
// hook runs AFTER this one - too late to drain what it schedules. `cleanup()` is idempotent, so
// RTL's own pass afterwards is a no-op. Then poll until react-modal has taken its portal back,
// which costs nothing for the tests that leave none behind.
//
// The loop counts iterations instead of watching a deadline on purpose: `vi.setSystemTime` at the
// bottom of this file freezes `Date.now()`, so a clock-based bound here would never expire.
const MODAL_PORTAL_CLASS = "cds--bmrg-modal-portal";
const DRAIN_INTERVAL_MS = 50;
const DRAIN_MAX_TICKS = 12; // 600ms > the 240ms react-modal defers by

async function unmountAndDrainReactModalPortals() {
  cleanup();
  if (typeof document === "undefined") return;
  for (let tick = 0; tick < DRAIN_MAX_TICKS; tick++) {
    if (document.body.getElementsByClassName(MODAL_PORTAL_CLASS).length === 0) return;
    await new Promise((resolve) => setTimeout(resolve, DRAIN_INTERVAL_MS));
  }
}

// RTL's default `findBy*`/`waitFor` window is 1000ms, chosen for cheap single-component renders.
// Nearly every spec here now renders a route tree whose server `loader` blocks first paint on a
// batch of (mocked) requests, and vitest runs those files across one worker per core - so the
// same wait that measures 189-311ms on an idle machine measures 420-1113ms when the full suite
// is in flight. That straddles 1000ms, which is exactly what made the suite fail ~50% of its
// runs: `WorkspaceDetailed > Visit Workspace Details tabs` (the rename round trip: 518/764/921/
// 978/1012/1113ms over six measured runs), `AdminTasks > renders a not-found state...`
// (502-1081ms) and `CreateToken > Fill out form` (420-822ms) were all completing, just late.
//
// One project-wide window replaces the per-file workarounds this problem had already started
// growing (Editor.spec.tsx's `LOADER_WAIT = { timeout: 15000 }`). It does NOT mask a regression:
// an element that never appears still fails the assertion - it takes 5s to say so instead of 1s.
// Deliberately not solved with `--retry`, which would turn a flaky test into a silent one.
configureTestingLibrary({ asyncUtilTimeout: 5000 });

// `findBy*`/`waitFor` can resolve BEFORE React has flushed the passive effects of the commit that
// satisfied them: the underlying MutationObserver fires during the commit's mutation phase, while
// `useEffect` callbacks are queued for after paint. The awaited element is therefore on screen
// while its component's mount effects are still pending - and the very next line of a test is
// usually a click on it.
//
// That is a live bug here, not a theoretical one. @boomerang-io/carbon-addons-boomerang-react's
// ModalFlow (dist/esm/components/FlowModal/FlowModal.js) keeps its open state internally and
// re-asserts the `isOpen` PROP from a mount effect - `useEffect(() => setState({ isOpen:
// props.isOpen }), [props.isOpen, setState])`, where `props.isOpen` defaults to false. A click
// that lands before that effect runs opens the modal and then has it closed again a moment later.
// Measured by polling the overlay every 25ms after the click in `CreateToken > Renders the
// server-driven permission grid`: a passing run reads `11111111111111...`, the failing run read
// `11111111000000...` - open for ~200ms, then gone, which is react-modal's `closeTimeoutMS: 240`
// animating out an `isOpen` that had already flipped back to false on the click's own tick.
//
// Flushing effects on the way out of every async utility makes tests interact with a settled
// tree, which is what they already assume. It fixes the whole class rather than one spec: the
// same pattern (`fireEvent.click(await screen.findByTestId("open-change-name-modal"))`) appears
// in WorkspaceDetailed and elsewhere.
const rtlAsyncWrapper = getTestingLibraryConfig().asyncWrapper;
configureTestingLibrary({
  asyncWrapper: async (cb) => {
    const result = await rtlAsyncWrapper(cb);
    await act(async () => {
      // The body only has to yield - React flushes its pending passive-effect queue on the way
      // out of an async act(), which is the point of entering one here.
      await Promise.resolve();
    });
    return result;
  },
});

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
