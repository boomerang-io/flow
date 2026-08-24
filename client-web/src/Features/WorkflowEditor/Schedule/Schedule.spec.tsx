import { screen } from "@testing-library/react";
import { Route } from "react-router-dom";
import { workspaces } from "ApiServer/fixtures";
import { appLink } from "Config/appConfig";
import { WorkflowStatus, type WorkflowCanvas } from "Types";
import { editorLoader } from "../editorRoute";
import Schedule from "./index";

const WORKSPACE = workspaces.content[0].name;
const WORKFLOW = "5e877d1f4bbc6e0001c51e12";

/*
 * This spec previously rendered `<Schedule summaryData={...} />` - a prop the component has never
 * had - and snapshotted the resulting crash: the committed snapshot was react-router's
 * "Unexpected Application Error! / Cannot read properties of undefined (reading 'name')" page,
 * which passed for as long as the component kept throwing in exactly the same place. Replaced
 * with a real render and real assertions.
 */

// Hand-built rather than spread from the untyped `workflows` fixture, several of whose fields
// don't satisfy the real Workflow type - the same technique Configure.spec.tsx uses.
const workflow: WorkflowCanvas = {
  id: WORKFLOW,
  name: "schedule-me",
  displayName: "Schedule Me",
  description: "",
  creationDate: "2026-01-01T00:00:00.000Z",
  status: WorkflowStatus.Active,
  version: 1,
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
  edges: [],
  nodes: [],
};

/*
 * The schedules and calendar now come from the editor route's loader, and that loader only issues
 * them when the route's splat is "schedule" - so the route has to be the real
 * "/:workspace/editor/:workflow/*" matched at the Schedules tab's URL, not a bare catch-all.
 * Rendering <Schedule> directly under a loader-less route leaves useEditorRouteData() undefined
 * and the component parked on its spinner, which is what this asserts against below.
 */
/*
 * The loader blocks first paint on the whole batch of (mocked) requests the editor needs, so
 * until it resolves the router renders its HydrateFallback (nothing) - RTL's default 1000ms
 * findBy window is not enough on a machine running the full suite across workers, where the
 * previous useQuery version painted a spinner immediately. Same constants as Editor.spec.tsx.
 */
const LOADER_WAIT = { timeout: 15000 };
const TEST_TIMEOUT = 30000;

function renderSchedule() {
  return global.rtlContextRouterRender(
    <Route path="/:workspace/editor/:workflow/*" loader={editorLoader} element={<Schedule workflow={workflow} />} />,
    { route: appLink.editorSchedule({ workspace: WORKSPACE, workflow: WORKFLOW }) },
  );
}

describe("Schedule", () => {
  it(
    "renders the workflow's schedules from the route loader",
    async () => {
      renderSchedule();

      // Names from ApiServer/fixtures/workflowSchedules.js, rendered by SchedulePanelList.
      expect(await screen.findByText("Trigger", undefined, LOADER_WAIT)).toBeInTheDocument();
      expect(screen.getByText("Daily event")).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );

  it(
    "renders the calendar alongside the schedule list",
    async () => {
      renderSchedule();

      await screen.findByText("Trigger", undefined, LOADER_WAIT);
      // react-big-calendar's month-view toolbar and title - proves the calendar half mounted
      // rather than the component bailing out to its spinner or ErrorDragon. The test clock is
      // frozen to 2020-01-01 (setupTests.tsx), so the month label is deterministic.
      expect(screen.getByRole("button", { name: "Today" })).toBeInTheDocument();
      expect(screen.getByText("January 2020")).toBeInTheDocument();
    },
    TEST_TIMEOUT,
  );
});
