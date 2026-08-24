import moment from "moment-timezone";
import queryString from "query-string";
import { tokenAction, workflowTokensLoader, TOKEN_INTENTS } from "Components/TokenSection/tokenRoute";
import type { TokenActionResult } from "Components/TokenSection/tokenRoute";
import type { TokenSectionRouteData } from "Components/TokenSection/tokenRouteData";
import { HttpMethod, scheduleStatusOptions } from "Constants";
import { queryStringOptions } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type { CalendarEntry, PaginatedSchedulesResponse, WorkflowCanvas } from "Types";
import type { EditorData, EditorRouteData, EditorScheduleData } from "./editorRouteData";

/*
 * Server half of the workflow editor route (`/:workspace/editor/:workflow/*`,
 * app/routes/editor.tsx) - the loader/action bodies that replaced the six useQuery calls in
 * Editor.tsx, Configure.tsx's GitHub-installation useQuery, and Schedule/Schedule.tsx's
 * schedules/calendar useQuery pair.
 *
 * Node-only (imports Config/serverFetch) - import this from app/routes/* only, never from a
 * component. Components read the result through useEditorRouteData() in editorRouteData.ts.
 *
 * Follows the GlobalParameters.tsx reference conversion and the shared-loader shape of
 * Components/TokenSection/tokenRoute.ts, which this composes: the Configure > Tokens tab renders
 * <TokenSection> inside this route, so the one loader has to carry `tokenSection` as well.
 */

const ACTIVE_TASKS_QUERY = queryString.stringify({ statuses: "active" });

/*
 * Which URL segment the Schedules tab lives at. The route is a splat
 * (`/:workspace/editor/:workflow/*`) whose remainder Editor.tsx re-matches with a descendant
 * <Routes>, so `params["*"]` is how the loader knows which tab is being asked for - see
 * AppPath.EditorSchedule in Config/appConfig.ts.
 */
const SCHEDULE_TAB = "schedule";

// Settled, not `all`: one failed read must not blank the others out. Each of the six core reads
// below independently contributed `isError` to Editor.tsx's single <Error /> gate, which this
// reproduces by OR-ing the rejections into one `errorLoading` flag.
function valueOf<T>(result: PromiseSettledResult<{ data: T }>): T | undefined {
  return result.status === "fulfilled" ? result.value.data : undefined;
}

async function loadSchedule(
  request: Request,
  { workspace, workflow }: { workspace: string; workflow: string },
): Promise<EditorScheduleData> {
  const api = serverFetch(request);
  const url = new URL(request.url);
  /*
   * Computed per request, not hoisted to a module constant: this module is imported once into a
   * long-lived Node server, so a module-level `moment().startOf("month")` would freeze the
   * default calendar window at process boot.
   */
  const { fromDate = moment().startOf("month").unix(), toDate = moment().endOf("month").unix() } = queryString.parse(
    url.search,
    queryStringOptions,
  );

  let schedulesData: PaginatedSchedulesResponse | undefined;
  let errorLoadingSchedules = false;
  try {
    const schedulesUrlQuery = queryString.stringify(
      { statuses: scheduleStatusOptions.map((statusObj) => statusObj.value), workflows: workflow },
      queryStringOptions,
    );
    const response = await api.get(serviceUrl.workspace.schedule.getSchedules({ workspace, query: schedulesUrlQuery }));
    schedulesData = response.data;
  } catch (error) {
    errorLoadingSchedules = true;
  }

  /*
   * Genuinely dependent on the schedules fetch (it needs the resolved schedule ids), so an
   * explicit await rather than a parallel call gated after the fact - the client-side version
   * expressed the same dependency with react-query's `enabled: schedules.length > 0`.
   */
  let calendarEntries: Array<CalendarEntry> = [];
  let errorLoadingCalendar = false;
  const scheduleIds = (schedulesData?.content ?? []).map((schedule) => schedule.id);
  if (scheduleIds.length > 0) {
    try {
      const calendarUrlQuery = queryString.stringify({ schedules: scheduleIds, fromDate, toDate }, queryStringOptions);
      const response = await api.get(
        serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace, query: calendarUrlQuery }),
      );
      calendarEntries = response.data ?? [];
    } catch (error) {
      errorLoadingCalendar = true;
    }
  }

  return { schedulesData, calendarEntries, errorLoadingSchedules, errorLoadingCalendar };
}

export async function editorLoader({
  params,
  request,
}: {
  params: { workspace?: string; workflow?: string; "*"?: string };
  request: Request;
}): Promise<EditorRouteData & TokenSectionRouteData> {
  const workspace = String(params.workspace ?? "");
  const workflow = String(params.workflow ?? "");
  const api = serverFetch(request);
  /*
   * The version being viewed moved from Editor.tsx's `revisionNumber` useState to a `?version=`
   * search param, because that is what re-runs this loader - the version switcher now navigates
   * instead of calling a setter. Absent means "latest", exactly as the empty-string initial state
   * did (serviceUrl.getWorkflowCompose omits the query param for a falsy version).
   */
  const version = new URL(request.url).searchParams.get("version") ?? "";

  const [workflowResult, workflowsResult, changeLogResult, availableParametersResult, tasksResult, workspaceTasksResult] =
    await Promise.allSettled([
      api.get(serviceUrl.workspace.workflow.getWorkflowCompose({ workspace, workflow, version })),
      api.get(serviceUrl.workspace.workflow.getWorkflows({ workspace })),
      api.get(serviceUrl.workspace.workflow.getWorkflowChangelog({ workspace, workflow })),
      api.get(serviceUrl.workspace.workflow.getAvailableParameters({ workspace, workflow })),
      api.get(serviceUrl.task.queryTasks({ query: ACTIVE_TASKS_QUERY })),
      api.get(serviceUrl.workspace.task.queryTasks({ workspace, query: ACTIVE_TASKS_QUERY })),
    ]);

  // Configure.tsx renders on every tab (Editor.tsx mounts it unconditionally so its Formik state
  // survives tab switches), so its installation lookup is unconditional here too - matching the
  // `enabled: Boolean(params.workspace)` useQuery it replaces.
  let githubAppInstallation: any | null = null;
  try {
    const response = await api.get(serviceUrl.getGitHubAppInstallationForWorkspace({ workspace }));
    githubAppInstallation = response.data ?? null;
  } catch (error) {
    githubAppInstallation = null;
  }

  const workflowData = valueOf<WorkflowCanvas>(workflowResult);

  const editor: EditorData = {
    workflow: workflowData,
    workflows: valueOf(workflowsResult),
    changeLog: valueOf(changeLogResult),
    availableParameters: valueOf(availableParametersResult),
    tasks: valueOf(tasksResult),
    workspaceTasks: valueOf(workspaceTasksResult),
    errorLoading: [
      workflowResult,
      workflowsResult,
      changeLogResult,
      availableParametersResult,
      tasksResult,
      workspaceTasksResult,
    ].some((result) => result.status === "rejected"),
    githubAppInstallation,
    /*
     * Filtered by the workflow's own name, as Schedule/Schedule.tsx did with
     * `props.workflow.name`. Taken from the resolved compose rather than `params.workflow`
     * because the two differ: appLink.editorCanvas builds the URL from `workflow.name` today,
     * but every spec (and any older bookmark) addresses the editor by workflow id.
     */
    schedule:
      params["*"] === SCHEDULE_TAB
        ? await loadSchedule(request, { workspace, workflow: workflowData?.name ?? workflow })
        : null,
  };

  // Delegated rather than re-specified: workflowTokensLoader already owns the "which tokens does
  // the Configure > Tokens tab show" contract, and three other routes share the same helper.
  return { editor, ...(await workflowTokensLoader({ params, request })) };
}

export type EditorActionResult =
  | { ok: true; intent: "createRevision"; workflow: WorkflowCanvas }
  | { ok: false; intent: "createRevision" }
  | TokenActionResult;

/*
 * A route has exactly one action, and this one serves two independent groups of write sites that
 * both submit through a bare useFetcher() (which resolves to the nearest matched route - this
 * one): the Configure > Tokens tab's create/delete, and the header's "create new version". They
 * are dispatched on `intent`, the same way app/routes/profile.tsx composes tokenAction with the
 * profile's own action - and for the same reason (tokenAction must not be handed an intent it
 * does not own).
 *
 * The body of a Request can only be read once, so the intent is peeked off a clone and the
 * untouched original handed to whichever branch owns it.
 */
export async function editorAction({
  params,
  request,
}: {
  params: { workspace?: string; workflow?: string };
  request: Request;
}): Promise<EditorActionResult> {
  const formData = await request.clone().formData();
  const intent = String(formData.get("intent"));

  if ((TOKEN_INTENTS as readonly string[]).includes(intent)) {
    return tokenAction({ request });
  }

  // JSON in a form field rather than encType:"application/json", matching the GlobalParameters
  // and tokenRoute conversions - it keeps one fetcher able to carry both shapes of payload.
  const body = JSON.parse(String(formData.get("revision")));
  try {
    const response = await serverFetch(request)({
      url: serviceUrl.workspace.workflow.putApplyWorkflowCompose({
        workspace: String(params.workspace ?? ""),
        workflow: String(params.workflow ?? ""),
      }),
      data: body,
      method: HttpMethod.Put,
    });
    return { ok: true, intent: "createRevision", workflow: response.data };
  } catch (error) {
    return { ok: false, intent: "createRevision" };
  }
}
