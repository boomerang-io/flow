import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import moment from "moment-timezone";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { scheduleAction } from "Features/Schedules/scheduleRoute";
import { DATETIME_LOCAL_INPUT_FORMAT } from "Utils/dateHelper";
import { WorkflowStatus, type Workflow } from "Types";
import { renderWithContext } from "Utils/testing/render";
import ScheduleCreator from "./ScheduleCreator";

const WORKSPACE = "test-workspace";

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

// Route-module test pattern (see CreateWorkflow.spec.tsx): ScheduleCreator submits its create
// through a bare useFetcher(), which resolves against whichever route is in context - here the
// same `/:workspace/schedules` + scheduleAction shape app/routes/schedules.tsx wires up, so the
// action's `params.workspace` read behaves as it does live.
//
// `includeWorkflowDropdown=false` with an explicit `workflow` prop mirrors how
// WorkflowEditor/Schedule/Schedule.tsx renders this component (a single, already-known workflow,
// no picker) - `handleSubmit` reads `workflow.name` unconditionally, so a workflow must be
// supplied one way or the other.
function renderCreator(overrides: Partial<React.ComponentProps<typeof ScheduleCreator>> = {}) {
  return renderWithContext(
    <ScheduleCreator
      includeWorkflowDropdown={false}
      isModalOpen
      onCloseModal={() => {}}
      workflow={workflow}
      {...overrides}
    />,
    { path: "/:workspace/schedules", action: scheduleAction, route: `/${WORKSPACE}/schedules` },
  );
}

async function fillMinimumRunOnceForm() {
  await userEvent.type(screen.getByLabelText("Name"), "Nightly Backup");
  fireEvent.change(screen.getByLabelText("Date and Time"), {
    target: { value: moment().add(1, "day").format(DATETIME_LOCAL_INPUT_FORMAT) },
  });
}

describe("ScheduleCreator", () => {
  // One flow per render, not one assertion per test: rendering the modal a second time in a
  // fresh `test()` in this file reliably leaves ComposedModal/Carbon's Modal in a state where
  // nothing renders the second time round - a pre-existing quirk of this shared modal plumbing
  // under jsdom (see the `hidden: true` notes in ScheduleEditor.spec.tsx for the related
  // aria-hidden symptom). Each test below therefore covers a whole submit flow.
  test("submits a new runOnce schedule through the route action and closes on success", async () => {
    let createdBody: any;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json({ ...createdBody, id: "new-schedule" }, { status: 201 });
      }),
    );

    const onCloseModal = vi.fn();
    renderCreator({ onCloseModal });

    expect(await screen.findByText("Create a Schedule")).toBeInTheDocument();

    await fillMinimumRunOnceForm();

    // Labels: the Creatable emits "key:value" strings, which handleSubmit converts to the
    // Record<string, string> the API takes. This block was commented out in the v4 import (and
    // the dead code inside called .length/.map on a Record), so labels silently never reached
    // the API - the `labels` assertion below is what proves that fixed (pinned behaviour, must
    // survive the fetcher migration).
    await userEvent.type(screen.getByLabelText("Label key"), "level");
    await userEvent.type(screen.getByLabelText("Label value"), "important");
    await userEvent.click(screen.getByRole("button", { name: "Add" }));

    const createButton = await screen.findByRole("button", { name: "Create" }, { timeout: 3000 });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    await waitFor(() =>
      expect(createdBody).toMatchObject({
        name: "Nightly Backup",
        type: "runOnce",
        labels: { level: "important" },
      }),
    );

    // The await-contract rework's user-visible half: the modal closes only once the fetcher
    // settles ok (ComposedModal's closeModal chains to onCloseModal). notify()'s toast isn't
    // observable here (no ToastContainer in the harness - see setupTests.tsx).
    await waitFor(() => expect(onCloseModal).toHaveBeenCalled());
  });

  test("keeps the modal open with the inline error on failure, then Try again succeeds", async () => {
    let createdBody: any;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), () =>
        HttpResponse.json({}, { status: 500 }),
      ),
    );

    const onCloseModal = vi.fn();
    renderCreator({ onCloseModal });

    expect(await screen.findByText("Create a Schedule")).toBeInTheDocument();

    await fillMinimumRunOnceForm();

    const createButton = await screen.findByRole("button", { name: "Create", hidden: true }, { timeout: 3000 });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    // Failure: inline notification, modal still open, button flips to "Try again" - the exact
    // behaviour the old `await handleSubmit()` try/catch gave.
    expect(await screen.findByText("Something's Wrong")).toBeInTheDocument();
    expect(onCloseModal).not.toHaveBeenCalled();

    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json({ ...createdBody, id: "new-schedule" }, { status: 201 });
      }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Try again", hidden: true }));

    await waitFor(() => expect(createdBody).toMatchObject({ name: "Nightly Backup", type: "runOnce" }));
    await waitFor(() => expect(onCloseModal).toHaveBeenCalled());
  });
});
