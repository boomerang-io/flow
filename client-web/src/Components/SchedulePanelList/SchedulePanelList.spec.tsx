import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import type { PaginatedSchedulesResponse } from "Types";
import SchedulePanelList from "./SchedulePanelList";

function buildSchedulesData(): PaginatedSchedulesResponse {
  return {
    content: [
      {
        id: "1",
        name: "Nightly Backup",
        description: "Backs up nightly",
        status: "active",
        type: "cron",
        cronSchedule: "0 0 * * *",
        timezone: "UTC",
        workflowRef: "wf-1",
        nextScheduleDate: "2026-08-25T00:00:00Z",
      },
      {
        id: "2",
        name: "One-off Migration",
        description: "Runs once",
        status: "inactive",
        type: "runOnce",
        dateSchedule: "2026-08-25T00:00:00Z",
        timezone: "UTC",
        workflowRef: "wf-2",
        nextScheduleDate: "2026-08-25T00:00:00Z",
      },
    ],
    number: 0,
    size: 2,
    totalElements: 2,
    totalPages: 1,
    first: true,
    last: true,
    numberOfElements: 2,
    empty: false,
    sort: { sorted: false, empty: true, unsorted: true },
  };
}

// SchedulePanelList is rendered as a bare component (no Route needed) - `rtlContextRouterRender`
// wraps anything that isn't itself a <Route>/<Fragment> in a catch-all route (see
// setupTests.tsx's buildRoutes), which is enough router context for the component's
// `useRevalidator()` call and its shared react-query mutations (see the rationale comment on
// ScheduledListItem in SchedulePanelList.tsx for why those stay on react-query).
function renderList(overrides: Partial<React.ComponentProps<typeof SchedulePanelList>> = {}) {
  return global.rtlContextRouterRender(
    <SchedulePanelList
      getCalendarUrl="http://localhost/calendar"
      getSchedulesUrl="http://localhost/schedules"
      includeStatusFilter
      schedulesIsLoading={false}
      schedulesData={buildSchedulesData()}
      setActiveSchedule={() => {}}
      setIsCreatorOpen={() => {}}
      setIsEditorOpen={() => {}}
      {...overrides}
    />,
  );
}

describe("SchedulePanelList", () => {
  test("renders every schedule when no filter is applied", () => {
    renderList();
    expect(screen.getByText("Nightly Backup")).toBeInTheDocument();
    expect(screen.getByText("One-off Migration")).toBeInTheDocument();
  });

  // Regression: the status MultiSelect used to pass `selectedItem` (singular, wrong shape) where
  // Carbon requires `selectedItems`, so a chosen filter never actually narrowed the list. It now
  // passes `selectedItems={scheduleStatusOptions.filter(...)}` correctly.
  test("choosing a status filters the list down to matching schedules", async () => {
    renderList();
    expect(screen.getByText("Nightly Backup")).toBeInTheDocument();
    expect(screen.getByText("One-off Migration")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("combobox", { name: /Filter by status/i }));
    await userEvent.click(await screen.findByRole("option", { name: "Disabled" }));

    expect(await screen.findByText("One-off Migration")).toBeInTheDocument();
    expect(screen.queryByText("Nightly Backup")).not.toBeInTheDocument();
  });

  // Regression: the Search box used to read `e.currentTarget`, but Carbon's Search `onChange`
  // fires a plain `{target, type}` object with no `currentTarget`, so typing filtered nothing. It
  // now reads `e.target.value`.
  test("typing in the search box filters the list", async () => {
    renderList({ includeStatusFilter: false });

    await userEvent.type(screen.getByPlaceholderText("Search Schedules"), "Nightly");

    expect(await screen.findByText("Nightly Backup")).toBeInTheDocument();
    expect(screen.queryByText("One-off Migration")).not.toBeInTheDocument();
  });

  test("shows an empty state when no schedules match the search", async () => {
    renderList({ includeStatusFilter: false });

    await userEvent.type(screen.getByPlaceholderText("Search Schedules"), "no such schedule");

    expect(await screen.findByText("No matching schedules found")).toBeInTheDocument();
  });
});
