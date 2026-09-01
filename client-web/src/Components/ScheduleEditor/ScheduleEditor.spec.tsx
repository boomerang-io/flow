import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { scheduleAction } from "Features/Schedules/scheduleRoute";
import { WorkflowStatus, type ScheduleUnion, type Workflow } from "Types";
import { renderWithContext } from "Utils/testing/render";
import ScheduleEditor from "./ScheduleEditor";

const WORKSPACE = "test-workspace";

// Built by hand for the same reason ScheduleCreator.spec.tsx does it - see the comment there.
const workflow: Workflow = {
  id: "wf-1",
  name: "nightly-backup-workflow",
  displayName: "Nightly Backup Workflow",
  creationDate: "2026-01-01T00:00:00.000Z",
  status: WorkflowStatus.Active,
  version: 1,
  description: "",
  icon: "workflow",
  params: [],
  tasks: [],
  changelog: { author: "", reason: "", date: "" },
  triggers: {
    event: { enabled: false, conditions: [] },
    github: { enabled: false, conditions: [] },
    manual: { enabled: true, conditions: [] },
    schedule: { enabled: true, conditions: [] },
    webhook: { enabled: false, conditions: [] },
  },
  upgradesAvailable: false,
  workspaces: [],
};

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

// Route-module test pattern (see ScheduleCreator.spec.tsx): ScheduleEditor submits its update
// through a bare useFetcher(), so it renders under the same `/:workspace/schedules` +
// scheduleAction shape app/routes/schedules.tsx wires up.
function renderEditor(overrides: Partial<React.ComponentProps<typeof ScheduleEditor>> = {}) {
  return renderWithContext(
    <ScheduleEditor includeWorkflowDropdown={false} isModalOpen onCloseModal={() => {}} schedule={schedule} {...overrides} />,
    { path: "/:workspace/schedules", action: scheduleAction, route: `/${WORKSPACE}/schedules` },
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

  // The other half of the same defect: the submit-side labels block was commented out in the v4
  // import, so even labels that loaded correctly were dropped on save (the PUT body always carried
  // `labels: {}`). handleSubmit now converts the Creatable's "key:value" strings back into the
  // Record<string, string> the API takes, so an edit round-trips the existing labels and picks up
  // a newly added one.
  test("sends the form's labels on save", async () => {
    let updatedBody: any;
    server.use(
      http.put(serviceUrl.workspace.schedule.putSchedule({ workspace: ":workspace" }), async ({ request }) => {
        updatedBody = await request.json();
        return HttpResponse.json(updatedBody, { status: 200 });
      }),
    );

    // `workflow` is required for a save: handleSubmit reads `workflow.name` off the form values,
    // which ScheduleManagerForm seeds from this prop.
    const onCloseModal = vi.fn();
    renderEditor({ workflow, onCloseModal });
    await screen.findByText("Edit a Schedule");

    await userEvent.type(screen.getByLabelText("Label key"), "tier");
    await userEvent.type(screen.getByLabelText("Label value"), "gold");
    // `hidden: true`: react-modal's ariaHideApp puts aria-hidden="true" on the #app element while
    // a modal is open and only restores it on close, so by the fourth render in this file the
    // whole modal tree is out of the accessibility tree and every plain byRole query misses it.
    // A harness quirk, not a component one - byLabelText above ignores aria-hidden and finds the
    // same inputs.
    await userEvent.click(screen.getByRole("button", { name: "Add", hidden: true }));

    const saveButton = screen.getByRole("button", { name: "Update", hidden: true });
    await waitFor(() => expect(saveButton).toBeEnabled());
    await userEvent.click(saveButton);

    await waitFor(() =>
      expect(updatedBody).toMatchObject({ labels: { env: "prod", team: "core", tier: "gold" } }),
    );
    // Fetcher-settle close (the await-contract rework): the modal closes only once the update
    // actually succeeded - see ScheduleCreator.spec.tsx for the failure half of the contract.
    await waitFor(() => expect(onCloseModal).toHaveBeenCalled());
  });
});
