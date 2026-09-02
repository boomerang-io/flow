import { screen } from "@testing-library/react";
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
});
