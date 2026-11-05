import moment from "moment";
import { MemoryRouter, type NavigateFunction } from "react-router-dom";
import ActivityTable from "Features/Activity/ActivityTable";
import ChangeLogTable from "Features/WorkflowEditor/ChangeLog/ChangeLogTable";
import { DATETIME_DISPLAY_FORMAT } from "Utils/dateHelper";
import { hydrateInAnotherTimezone } from "Utils/testing/hydration";
import FormattedDate from "./FormattedDate";

// 2026-03-01T22:30:00Z is 2026-03-02 09:30 AM in Sydney: the viewer's timezone moves the date as
// well as the hour.
const START = "2026-03-01T22:30:00.000Z";
const IN_UTC = "2026-03-01 10:30 PM";
const IN_SYDNEY = "2026-03-02 09:30 AM";

function RawMoment({ value }: { value: string }) {
  return <time>{moment(value).format(DATETIME_DISPLAY_FORMAT)}</time>;
}

describe("FormattedDate", () => {
  // React logs every mismatch it recovers from; the recoverable errors are what these assert on.
  let restoreConsole: () => void;
  beforeEach(() => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    restoreConsole = () => consoleError.mockRestore();
  });
  afterEach(() => restoreConsole());

  it("hydrates without a mismatch for a viewer outside UTC, then shows the viewer's time", async () => {
    const { serverText, hydratedText, recoverableErrors } = await hydrateInAnotherTimezone(
      <time>
        <FormattedDate value={START} format={DATETIME_DISPLAY_FORMAT} />
      </time>,
    );

    expect(recoverableErrors).toEqual([]);
    expect(serverText).toBe(IN_UTC);
    expect(hydratedText).toBe(IN_SYDNEY);
  });

  // The control: a format straight in render, which is what the pages did, fails the same harness.
  it("is needed: a plain moment format in render mismatches for the same viewer", async () => {
    const { recoverableErrors } = await hydrateInAnotherTimezone(<RawMoment value={START} />);

    expect(recoverableErrors.length).toBeGreaterThan(0);
  });

  it("keeps a page's table intact: the Activity run list", async () => {
    const run = { id: "run-1", workflowName: "hello", trigger: "manual", status: "succeeded", startTime: START };
    const { hydratedText, recoverableErrors } = await hydrateInAnotherTimezone(
      <MemoryRouter initialEntries={["/tyson-workspace/activity"]}>
        <ActivityTable
          isLoading={false}
          sort="creationDate"
          order="DESC"
          tableData={{ number: 0, size: 10, totalElements: 1, content: [run] }}
          location={{}}
          navigate={vi.fn() as unknown as NavigateFunction}
          updateHistorySearch={vi.fn()}
          workflowNameMap={{}}
        />
      </MemoryRouter>,
    );

    expect(recoverableErrors).toEqual([]);
    expect(hydratedText).toContain(IN_SYDNEY);
  });

  it("keeps a page's table intact: the workflow Change Log", async () => {
    const { hydratedText, recoverableErrors } = await hydrateInAnotherTimezone(
      <ChangeLogTable changeLog={[{ version: 1, author: "Jenny", reason: "Create workflow", date: START }]} />,
    );

    expect(recoverableErrors).toEqual([]);
    expect(hydratedText).toContain(IN_SYDNEY);
  });
});
