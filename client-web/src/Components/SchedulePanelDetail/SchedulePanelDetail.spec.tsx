import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ScheduleUnion } from "Types";
import { renderWithContext } from "Utils/testing/render";
import SchedulePanelDetail from "./SchedulePanelDetail";

const schedule: ScheduleUnion = {
  id: "1",
  name: "Nightly Backup",
  description: "Backs up nightly",
  status: "active",
  type: "cron",
  cronSchedule: "0 0 * * *",
  timezone: "UTC",
  workflowRef: "wf-1",
  nextScheduleDate: "2026-08-25T00:00:00Z",
  labels: { maintenance: "hello", daily: "yes" },
};

function renderDetail(event: ScheduleUnion) {
  return renderWithContext(
    <SchedulePanelDetail className="" event={event} isOpen setIsOpen={() => {}} setIsEditorOpen={() => {}} />,
  );
}

describe("SchedulePanelDetail", () => {
  // Regression (#387): the label rendering was commented out behind a TODO, so labels always
  // showed as "---" even on schedules that had them. Labels arrive as a string map (backend
  // WorkflowSchedule.labels is a Map<String,String>) and each entry renders as a key=value tag.
  test("renders each label as a key=value tag", () => {
    renderDetail(schedule);
    expect(screen.getByText("maintenance=hello")).toBeInTheDocument();
    expect(screen.getByText("daily=yes")).toBeInTheDocument();
  });

  test("falls back to --- when the schedule has no labels", () => {
    renderDetail({ ...schedule, labels: undefined });
    expect(screen.getByText("Labels")).toBeInTheDocument();
    expect(screen.queryByText(/=/)).not.toBeInTheDocument();
    expect(screen.getAllByText("---").length).toBeGreaterThan(0);
  });

  // The panel is the page's own element: open, it shows the schedule and closes from the overlay,
  // the close button or Escape; closed, it is hidden and holds nothing.
  test("opens with the schedule and closes from the overlay, the close button and Escape", async () => {
    const setIsOpen = vi.fn();
    const { rerender } = renderWithContext(
      <SchedulePanelDetail className="" event={schedule} isOpen setIsOpen={setIsOpen} setIsEditorOpen={() => {}} />,
    );
    const panel = screen.getByRole("dialog", { name: "Schedule Nightly Backup" });
    expect(panel).toHaveAttribute("aria-hidden", "false");
    expect(screen.getByRole("heading", { name: "Nightly Backup" })).toBeInTheDocument();

    userEvent.click(screen.getByTestId("schedule-panel-overlay"));
    userEvent.click(screen.getByRole("button", { name: "Close" }));
    userEvent.keyboard("{Escape}");
    expect(setIsOpen).toHaveBeenCalledTimes(3);
    expect(setIsOpen).toHaveBeenLastCalledWith(false);

    rerender(
      <SchedulePanelDetail
        className=""
        event={schedule}
        isOpen={false}
        setIsOpen={setIsOpen}
        setIsEditorOpen={() => {}}
      />,
    );
    expect(screen.queryByTestId("schedule-panel-overlay")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Nightly Backup" })).not.toBeInTheDocument();
  });
});
