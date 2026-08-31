import moment from "moment-timezone";
import queryString from "query-string";
import { tokenAction, workflowTokensLoader, TOKEN_INTENTS } from "Components/TokenSection/tokenRoute";
import type { TokenActionResult } from "Components/TokenSection/tokenRoute";
import { scheduleAction, SCHEDULE_INTENTS } from "Features/Schedules/scheduleRoute";
import type { ScheduleActionResult } from "Features/Schedules/scheduleRoute";
import type { TokenSectionRouteData } from "Components/TokenSection/tokenRouteData";
import { HttpMethod, scheduleStatusOptions } from "Constants";
import { queryStringOptions } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type {
  CalendarEntry,
  ChangeLog,
  PaginatedSchedulesResponse,
  PaginatedTaskResponse,
  PaginatedWorkflowResponse,
  WorkflowCanvas,
} from "Types";
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

/*
 * Settled, not rejecting: one failed read must not blank the others out. Each of the six core
 * reads independently contributed `isError` to Editor.tsx's single <Error /> gate, which the
 * loader reproduces by OR-ing the rejections into one `errorLoading` flag.
 *
 * `settle` rather than `Promise.allSettled` on the whole list because that list also carries a
 * call that settles itself (workflowTokensLoader) - mixing the two in one `Promise.all` keeps
 * every entry's result type honest instead of wrapping the already-safe one a second time.
 */
type Settled<T> = { ok: true; data: T } | { ok: false };

async function settle<T>(promise: Promise<{ data: T }>): Promise<Settled<T>> {
  try {
    return { ok: true, data: (await promise).data };
  } catch (error) {
    return { ok: false };
  }
}

function valueOf<T>(result: Settled<T>): T | undefined {
  return result.ok ? result.data : undefined;
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

  /*
   * One wave, not several. Every read below was an independent useQuery firing on mount, so they
   * all went out in parallel before this conversion; awaiting them in sequence here would turn
   * the editor's first paint into a chain of round trips, and the loader blocks that paint
   * entirely (there is no per-query loading state left to render behind). Only the schedules
   * chain below is genuinely dependent, and it is the one thing kept sequential.
   *
   * Configure.tsx renders on every tab (Editor.tsx mounts it unconditionally so its Formik state
   * survives tab switches), so its installation lookup is unconditional here too - matching the
   * `enabled: Boolean(params.workspace)` useQuery it replaces. The token section is fetched by
   * the shared workflowTokensLoader, which is itself a parallel pair.
   */
  const [
    workflowResult,
    workflowsResult,
    changeLogResult,
    availableParametersResult,
    tasksResult,
    workspaceTasksResult,
    githubResult,
    tokenSectionResult,
  ] = await Promise.all([
    settle(api.get<WorkflowCanvas>(serviceUrl.workspace.workflow.getWorkflowCompose({ workspace, workflow, version }))),
    settle(api.get<PaginatedWorkflowResponse>(serviceUrl.workspace.workflow.getWorkflows({ workspace }))),
    settle(api.get<ChangeLog>(serviceUrl.workspace.workflow.getWorkflowChangelog({ workspace, workflow }))),
    settle(api.get<Array<string>>(serviceUrl.workspace.workflow.getAvailableParameters({ workspace, workflow }))),
    settle(api.get<PaginatedTaskResponse>(serviceUrl.task.queryTasks({ query: ACTIVE_TASKS_QUERY }))),
    settle(api.get<PaginatedTaskResponse>(serviceUrl.workspace.task.queryTasks({ workspace, query: ACTIVE_TASKS_QUERY }))),
    settle(api.get<any>(serviceUrl.getGitHubAppInstallationForWorkspace({ workspace }))),
    // Delegated rather than re-specified: workflowTokensLoader already owns the "which tokens
    // does the Configure > Tokens tab show" contract, and three other routes share the helper.
    workflowTokensLoader({ params, request }),
  ]);

  const githubAppInstallation = valueOf<any>(githubResult) ?? null;
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
    ].some((result) => !result.ok),
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

  return { editor, ...tokenSectionResult };
}

export type EditorActionResult =
  | { ok: true; intent: "createRevision"; workflow: WorkflowCanvas }
  | { ok: false; intent: "createRevision" }
  | TokenActionResult
  | ScheduleActionResult;

/*
 * A route has exactly one action, and this one serves three independent groups of write sites
 * that all submit through a bare useFetcher() (which resolves to the nearest matched route -
 * this one): the Configure > Tokens tab's create/delete, the header's "create new version", and
 * the Schedule tab's create/update/toggle/delete (the shared ScheduleCreator/ScheduleEditor/
 * SchedulePanelList components, whose other consumer is the Schedules page - see
 * Features/Schedules/scheduleRoute.ts). They are dispatched on `intent`, the same way
 * app/routes/profile.tsx composes tokenAction with the profile's own action - and for the same
 * reason (a delegate action must not be handed an intent it does not own; the schedule intents
 * are namespaced `createSchedule`/`deleteSchedule`/... precisely because TOKEN_INTENTS already
 * claims the bare "create"/"delete" in this route's namespace).
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

  if ((SCHEDULE_INTENTS as readonly string[]).includes(intent)) {
    return scheduleAction({ params, request });
  }

  /*
   * Rejecting the rest is load-bearing, not defensive tidiness - the same trap tokenAction
   * documents. Falling through to the revision branch for an unrecognised intent would
   * `JSON.parse(String(null))` into `null` and PUT an empty body over the workflow's compose,
   * destroying it. Consumers narrow on `intent`, so an "unknown" result is inert for them.
   */
  if (intent !== "createRevision") {
    return {
      ok: false,
      intent: "unknown",
      errorMessage: {
        title: "Unsupported Editor Action",
        message: `The workflow editor action does not handle the "${intent}" intent.`,
      },
    };
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
