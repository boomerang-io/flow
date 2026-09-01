import DateHelper, {
  transformTimeZone,
  DATETIME_LOCAL_DISPLAY_FORMAT,
  DATETIME_LOCAL_INPUT_FORMAT,
  getSimplifiedDuration,
  timeSecondsToTimeUnit,
} from "./dateHelper";

// Characterisation spec (see specifications/framework-review-wave.md item C3): pins the CURRENT
// hand-rolled output of dateHelper/timeHelper/timeSecondsToTimeUnit before they are re-implemented
// on moment/moment.duration(), so the refactor is provably output-preserving. These values were
// captured by hand-tracing the pre-refactor implementations - do not "fix" a value here to match
// a new implementation; if a deliberate output change is needed, change it here with a comment
// explaining why, not silently.

describe("DateHelper.padLeadingZero", () => {
  test("pads single digits with a leading zero", () => {
    expect(DateHelper.padLeadingZero(0)).toBe("00");
    expect(DateHelper.padLeadingZero(5)).toBe("05");
    expect(DateHelper.padLeadingZero(9)).toBe("09");
  });

  test("leaves double digits untouched", () => {
    expect(DateHelper.padLeadingZero(10)).toBe(10);
    expect(DateHelper.padLeadingZero(59)).toBe(59);
  });
});

describe("DateHelper.getFormattedDateTime", () => {
  test("formats M/D H:mm:ss from local date components", () => {
    // Constructed from local components so the assertion is timezone-independent: the getters
    // the function reads (getMonth/getDate/getHours/...) return exactly what was passed in,
    // regardless of the machine's TZ.
    expect(DateHelper.getFormattedDateTime(new Date(2024, 0, 5, 9, 3, 7))).toBe("1/5 9:03:07");
    expect(DateHelper.getFormattedDateTime(new Date(2024, 10, 25, 23, 59, 0))).toBe("11/25 23:59:00");
    expect(DateHelper.getFormattedDateTime(new Date(2024, 5, 1, 0, 0, 0))).toBe("6/1 0:00:00");
  });
});

describe("DateHelper.timeMillisecondsToTimeUnit", () => {
  test.each([
    [0, ""],
    [59 * 1000, "59 secs"],
    [61 * 1000, "1 min 1 sec"],
    [(3600 + 60 + 1) * 1000, "1 hr 1 min 1 sec"],
    [25 * 3600 * 1000, "25 hrs"],
    [3 * 24 * 3600 * 1000, "72 hrs"],
    [1000, "1 sec"],
    [999, "999 ms"],
    [1, "1 ms"],
  ])("timeMillisecondsToTimeUnit(%i) === %j", (ms, expected) => {
    expect(DateHelper.timeMillisecondsToTimeUnit(ms)).toBe(expected);
  });
});

describe("DateHelper.timeMinutesToTimeUnit", () => {
  test.each([
    [0, " "],
    [59, " 59 mins"],
    [61, "1 hr 1 min"],
    [25 * 60, "25 hrs "],
    [3 * 24 * 60, "72 hrs "],
    [1, " 1 min"],
  ])("timeMinutesToTimeUnit(%i) === %j (untrimmed - existing quirk, pinned deliberately)", (minutes, expected) => {
    expect(DateHelper.timeMinutesToTimeUnit(minutes)).toBe(expected);
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
  test("delegates to timeMillisecondsToTimeUnit on the elapsed ms", () => {
    vi.setSystemTime(new Date("2024-06-15T12:00:00.000Z"));
    expect(DateHelper.durationFromThenToNow("2024-06-15T11:59:00.000Z")).toBe("1 min");
    vi.setSystemTime(new Date("2020-01-01T00:00:00.000Z"));
  });
});

describe("DateHelper.determineUpdatedMessage", () => {
  test.each([
    [0, "just now"],
    [1, " 1 min ago"],
    [61, "1 hr 1 min ago"],
  ])("determineUpdatedMessage(%i) === %j", (minutesAgo, expected) => {
    expect(DateHelper.determineUpdatedMessage(minutesAgo)).toBe(expected);
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

describe("getSimplifiedDuration", () => {
  test.each([
    [0, "0s"],
    [59, "59s"],
    [61, "1min"],
    [3661, "1h"],
    [25 * 3600, "25h"],
    [3 * 24 * 3600, "72h"],
  ])("getSimplifiedDuration(%i) === %j", (seconds, expected) => {
    expect(getSimplifiedDuration(seconds)).toBe(expected);
  });
});

describe("timeSecondsToTimeUnit", () => {
  test.each([
    [0, "0 secs"],
    [59, "59 secs"],
    [61, "1 min 1 sec"],
    [3661, "1 hr 1 min"],
    [25 * 3600, "25 hrs "],
    [3 * 24 * 3600, "72 hrs "],
    [1, "1 sec"],
  ])("timeSecondsToTimeUnit(%i) === %j (trailing space when minutes empty - existing quirk, pinned deliberately)", (seconds, expected) => {
    expect(timeSecondsToTimeUnit(seconds)).toBe(expected);
  });
});
