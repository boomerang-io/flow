import { http, HttpResponse } from "msw";
import { Route } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { scheduleAction } from "Features/Schedules/scheduleRoute";
import type { PaginatedSchedulesResponse } from "Types";
import SchedulePanelList from "./SchedulePanelList";

const WORKSPACE = "test-workspace";

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

// Route-module test pattern (see ScheduleCreator.spec.tsx): the delete/toggle writes submit
// through bare useFetcher() calls, which resolve against the route in context - the same
// `/:workspace/schedules` + scheduleAction shape app/routes/schedules.tsx wires up.
function renderList(overrides: Partial<React.ComponentProps<typeof SchedulePanelList>> = {}) {
  return global.rtlContextRouterRender(
    <Route
      path="/:workspace/schedules"
      action={scheduleAction}
      element={
        <SchedulePanelList
          includeStatusFilter
          schedulesIsLoading={false}
          schedulesData={buildSchedulesData()}
          setActiveSchedule={() => {}}
          setIsCreatorOpen={() => {}}
          setIsEditorOpen={() => {}}
          {...overrides}
        />
      }
    />,
    { route: `/${WORKSPACE}/schedules` },
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

  // The two writes, migrated off react-query: each submits its intent through its own
  // useFetcher() to the route action (no revalidator.revalidate()/invalidateQueries left - the
  // fetcher settle revalidates the owning route's loader on its own).
  test("deleting from the confirm modal DELETEs the schedule through the route action", async () => {
    let deletedId: string | undefined;
    server.use(
      http.delete(serviceUrl.workspace.schedule.deleteSchedule({ workspace: ":workspace", id: ":id" }), ({ params }) => {
        deletedId = String(params.id);
        return HttpResponse.json({});
      }),
    );

    renderList();

    // Two cards render, one overflow menu each (accessible name = the iconDescription tooltip,
    // not the ariaLabel prop); list is name-sorted so index 0 = "Nightly Backup" (id "1").
    await userEvent.click(screen.getAllByRole("button", { name: "Schedule menu icon" })[0]);
    await userEvent.click(await screen.findByText("Delete"));

    expect(await screen.findByText("Delete Schedule?")).toBeInTheDocument();
    // The affirmative button is kind="danger", and Carbon's danger buttons prepend a hidden
    // "danger" description span - the accessible name is "danger Delete", so a regex, not the
    // literal. `hidden: true`: react-modal's ariaHideApp puts aria-hidden on #app while the
    // modal is open (see ScheduleEditor.spec.tsx for the same quirk).
    await userEvent.click(screen.getByRole("button", { name: /danger Delete/, hidden: true }));

    await waitFor(() => expect(deletedId).toBe("1"));
  });

  test("disabling from the confirm modal PUTs the flipped status through the route action", async () => {
    let updatedBody: any;
    server.use(
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), async ({ request }) => {
        updatedBody = await request.json();
        return HttpResponse.json(updatedBody);
      }),
    );

    renderList();

    // "Nightly Backup" (id "1") is active, so its menu offers "Disable".
    await userEvent.click(screen.getAllByRole("button", { name: "Schedule menu icon" })[0]);
    await userEvent.click(await screen.findByText("Disable"));

    expect(await screen.findByText("Disable Schedule?")).toBeInTheDocument();
    // Same last-match click as the delete test above.
    const disableButtons = screen.getAllByRole("button", { name: "Disable", hidden: true });
    await userEvent.click(disableButtons[disableButtons.length - 1]);

    await waitFor(() => expect(updatedBody).toMatchObject({ id: "1", status: "inactive" }));
  });
});
