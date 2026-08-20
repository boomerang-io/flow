import { screen } from "@testing-library/react";
import type { ScheduleUnion } from "Types";
import ScheduleEditor from "./ScheduleEditor";

const schedule: ScheduleUnion = {
  id: "sched-1",
  name: "Nightly Backup",
  description: "Backs up nightly",
  status: "active",
  type: "cron",
  cronSchedule: "0 0 * * *",
  timezone: "UTC",
  workflowRef: "wf-1",
  nextScheduleDate: "2026-08-25T00:00:00Z",
  labels: { env: "prod", team: "core" },
};

// ScheduleEditor stays on react-query's useMutation (see the rationale comment at the top of its
// component) - it's shared with WorkflowEditor/Schedule/Schedule.tsx, which this batch must not
// touch. `useRevalidator()` was added alongside the existing mutation/invalidateQueries flow.
function renderEditor(overrides: Partial<React.ComponentProps<typeof ScheduleEditor>> = {}) {
  return global.rtlContextRouterRender(
    <ScheduleEditor
      getCalendarUrl="http://localhost/calendar"
      getSchedulesUrl="http://localhost/schedules"
      includeWorkflowDropdown={false}
      isModalOpen
      onCloseModal={() => {}}
      schedule={schedule}
      {...overrides}
    />,
  );
}

describe("ScheduleEditor", () => {
  test("opens with the schedule's name and description prefilled", async () => {
    renderEditor();
    expect(await screen.findByText("Edit a Schedule")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Nightly Backup")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Backs up nightly")).toBeInTheDocument();
  });

  // Regression: Schedule.labels is a Record<string,string>, but the edit-load path used to branch
  // on `.length` (always undefined on a plain object) and iterate it as an array, so opening an
  // existing schedule for edit silently discarded its labels. ScheduleManagerForm now reads it via
  // `Object.keys(...).length`/`Object.entries(...)`, so the saved labels actually load.
  test("loads the schedule's saved labels into the form instead of discarding them", async () => {
    renderEditor();
    expect(await screen.findByText("env:prod")).toBeInTheDocument();
    expect(screen.getByText("team:core")).toBeInTheDocument();
  });

  test("renders no labels for a schedule that has none", async () => {
    renderEditor({ schedule: { ...schedule, labels: {} } });
    await screen.findByText("Edit a Schedule");
    expect(screen.queryByText("env:prod")).not.toBeInTheDocument();
  });
});
