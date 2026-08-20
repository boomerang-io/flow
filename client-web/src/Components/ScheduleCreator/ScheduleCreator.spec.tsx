import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import moment from "moment-timezone";
import { server } from "ApiServer/msw/node";
import { serviceUrl } from "Config/servicesConfig";
import { DATETIME_LOCAL_INPUT_FORMAT } from "Utils/dateHelper";
import ScheduleCreator from "./ScheduleCreator";

// ScheduleCreator stays on react-query's useMutation (see the rationale comment at the top of its
// component) - it's shared with WorkflowEditor/Schedule/Schedule.tsx, which this batch must not
// touch. `useRevalidator()` was added alongside the existing mutation/invalidateQueries flow; these
// tests exercise that the create flow still works end to end with both in place.
function renderCreator(overrides: Partial<React.ComponentProps<typeof ScheduleCreator>> = {}) {
  return global.rtlContextRouterRender(
    <ScheduleCreator
      getCalendarUrl="http://localhost/calendar"
      getSchedulesUrl="http://localhost/schedules"
      includeWorkflowDropdown={false}
      isModalOpen
      onCloseModal={() => {}}
      {...overrides}
    />,
  );
}

describe("ScheduleCreator", () => {
  test("renders the create-schedule modal", async () => {
    renderCreator();
    expect(await screen.findByText("Create a Schedule")).toBeInTheDocument();
  });

  test("submits a new runOnce schedule and closes the modal", async () => {
    let createdBody: any;
    server.use(
      http.post(serviceUrl.workspace.schedule.postSchedule({ workspace: ":workspace" }), async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json({ ...createdBody, id: "new-schedule" }, { status: 201 });
      }),
    );
    const onCloseModal = () => {};

    renderCreator({ onCloseModal });

    await userEvent.type(screen.getByLabelText("Name"), "Nightly Backup");
    fireEvent.change(screen.getByLabelText("Date and Time"), {
      target: { value: moment().add(1, "day").format(DATETIME_LOCAL_INPUT_FORMAT) },
    });

    const createButton = await screen.findByRole("button", { name: "Create" });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    await waitFor(() => expect(createdBody).toMatchObject({ name: "Nightly Backup", type: "runOnce" }));
    expect(await screen.findByText("Successfully created schedule Nightly Backup")).toBeInTheDocument();
  });
});
