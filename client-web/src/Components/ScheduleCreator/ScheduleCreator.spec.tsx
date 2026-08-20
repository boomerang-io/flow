import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import moment from "moment-timezone";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { DATETIME_LOCAL_INPUT_FORMAT } from "Utils/dateHelper";
import { WorkflowStatus, type Workflow } from "Types";
import ScheduleCreator from "./ScheduleCreator";

// Built by hand rather than spreading the `workflows` ApiServer fixture: that fixture (untyped
// .js, predating the webapp/API type alignment noted in CLAUDE.md) has several fields that don't
// satisfy the real Workflow type (e.g. trigger conditions missing `values`), which would need
// fixing in ApiServer/fixtures - out of this batch's scope. No `params`, so the generated form
// doesn't grow an (unrelated) required workflow-parameter field.
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

// ScheduleCreator stays on react-query's useMutation (see the rationale comment at the top of its
// component) - it's shared with WorkflowEditor/Schedule/Schedule.tsx, which this batch must not
// touch. `useRevalidator()` was added alongside the existing mutation/invalidateQueries flow; these
// tests exercise that the create flow still works end to end with both in place.
//
// `includeWorkflowDropdown=false` with an explicit `workflow` prop mirrors how
// WorkflowEditor/Schedule/Schedule.tsx renders this component (a single, already-known workflow,
// no picker) - `handleSubmit` reads `workflow.name` unconditionally, so a workflow must be
// supplied one way or the other.
function renderCreator(overrides: Partial<React.ComponentProps<typeof ScheduleCreator>> = {}) {
  return global.rtlContextRouterRender(
    <ScheduleCreator
      getCalendarUrl="http://localhost/calendar"
      getSchedulesUrl="http://localhost/schedules"
      includeWorkflowDropdown={false}
      isModalOpen
      onCloseModal={() => {}}
      workflow={workflow}
      {...overrides}
    />,
  );
}

describe("ScheduleCreator", () => {
  // One test, not two: rendering the modal a second time in a fresh `test()` in this file
  // reliably leaves ComposedModal/Carbon's Modal in a state where nothing (including the
  // "Create"-schedule form and buttons) renders the second time round - a pre-existing quirk of
  // this shared modal plumbing under jsdom, unrelated to the useRevalidator() addition being
  // verified here. Covering both the initial render and the submit flow in one test sidesteps it.
  test("renders the create-schedule modal and submits a new runOnce schedule", async () => {
    let createdBody: any;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json({ ...createdBody, id: "new-schedule" }, { status: 201 });
      }),
    );

    renderCreator();

    expect(await screen.findByText("Create a Schedule")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Name"), "Nightly Backup");
    fireEvent.change(screen.getByLabelText("Date and Time"), {
      target: { value: moment().add(1, "day").format(DATETIME_LOCAL_INPUT_FORMAT) },
    });

    const createButton = await screen.findByRole("button", { name: "Create" }, { timeout: 3000 });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    // notify() renders via react-toastify's `toast()`, which needs a <ToastContainer/> mounted
    // somewhere in the tree to actually paint anything - none is wired up in this test harness
    // (see setupTests.tsx), so toast content isn't observable here. `createdBody` is the
    // meaningful assertion: it proves the mutation fired with the right payload.
    await waitFor(() => expect(createdBody).toMatchObject({ name: "Nightly Backup", type: "runOnce" }));
  });
});
