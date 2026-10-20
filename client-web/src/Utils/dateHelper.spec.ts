import DateHelper, {
  transformTimeZone,
  DATETIME_LOCAL_DISPLAY_FORMAT,
  DATETIME_LOCAL_INPUT_FORMAT,
  formatDuration,
} from "./dateHelper";

// Characterisation spec: pins the CURRENT
// hand-rolled output of dateHelper/timeHelper before they are re-implemented
// on moment/moment.duration(), so the refactor is provably output-preserving. These values were
// captured by hand-tracing the pre-refactor implementations - do not "fix" a value here to match
// a new implementation; if a deliberate output change is needed, change it here with a comment
// explaining why, not silently.

// Every run and task duration renders through this one formatter, so the same duration reads the
// same on Activity, the run page, a task row, Insights and the Quotas page.
describe("formatDuration", () => {
  test.each([
    [0, "0 s"],
    [999, "< 1 s"],
    [1000, "1 s"],
    [59 * 1000, "59 s"],
    [61 * 1000, "1 min 1 s"],
    [22 * 60 * 1000, "22 min"],
    [(3600 + 60 + 1) * 1000, "1 h 1 min"],
    [25 * 3600 * 1000, "25 h"],
  ])("formatDuration(%i) === %j", (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected);
  });
});

describe("DateHelper.humanizedSimpleTimeAgo", () => {
  // setupTests.tsx already pins the global clock via vi.setSystemTime (not vi.useFakeTimers -
  // see setupTests.tsx:328-331); move it for this block, then restore its baseline in afterEach
  // rather than vi.useRealTimers(), which would undo the suite-wide mock for later tests.
  const now = new Date("2024-06-15T12:00:00.000Z");
  const SETUP_TESTS_BASELINE = new Date("2020-01-01T00:00:00.000Z");

  beforeEach(() => {
    vi.setSystemTime(now);
  });

  afterEach(() => {
    vi.setSystemTime(SETUP_TESTS_BASELINE);
  });

  test.each([
    ["45 sec ago", new Date(now.getTime() - 45 * 1000), "45 secs ago"],
    ["5 min ago", new Date(now.getTime() - 5 * 60 * 1000), "5 mins ago"],
    ["1 min ago (singular)", new Date(now.getTime() - 1 * 60 * 1000), "1 min ago"],
    ["2 hours ago", new Date(now.getTime() - 2 * 3600 * 1000), "2 hours ago"],
    ["3 days ago", new Date(now.getTime() - 3 * 24 * 3600 * 1000), "3 days ago"],
    ["2 months ago", new Date(now.getTime() - 62 * 24 * 3600 * 1000), "2 months ago"],
    ["2 years ago", new Date(now.getTime() - 800 * 24 * 3600 * 1000), "2 years ago"],
  ])("%s", (_label, datetimestamp, expected) => {
    expect(DateHelper.humanizedSimpleTimeAgo(datetimestamp)).toBe(expected);
  });
});

describe("DateHelper.durationFromThenToNow", () => {
  test("formats the elapsed ms like any other duration", () => {
    vi.setSystemTime(new Date("2024-06-15T12:00:00.000Z"));
    expect(DateHelper.durationFromThenToNow("2024-06-15T11:59:00.000Z")).toBe("1 min");
    vi.setSystemTime(new Date("2020-01-01T00:00:00.000Z"));
  });
});

describe("DateHelper named exports", () => {
  test("transformTimeZone", () => {
    const result = transformTimeZone("UTC");
    expect(result.value).toBe("UTC");
    expect(result.label).toBe("UTC (UTC +00:00)");
  });

  test("format constants are unchanged", () => {
    expect(DATETIME_LOCAL_DISPLAY_FORMAT).toBe("MMMM DD, YYYY h:mma");
    expect(DATETIME_LOCAL_INPUT_FORMAT).toBe("YYYY-MM-DDTHH:mm");
  });
});
