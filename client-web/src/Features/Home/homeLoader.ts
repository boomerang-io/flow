import moment from "moment";
import queryString from "query-string";
import { queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { Action, FlowUser, FlowWorkspaceSummary, PaginatedResponse, RunStatus, Schedule, WorkflowRun } from "Types";

/*
 * Home's read. The landing page rolls up every workspace the caller belongs to: today's run
 * counts, the actions waiting on a person, the latest runs, and the next scheduled run. None of
 * that exists as one endpoint, and the profile's per-workspace `insights` (workflow and member
 * counts) are computed inside the bootstrap that every route pays for - so the rollup is NOT added
 * there (see specifications/decisions/0088-home-rollup-fans-out-per-workspace.md). Instead this
 * loader fans out to the four existing per-workspace reads, all in one wave, and merges.
 *
 * Two waves, not one: the workspace list has to come first (a route loader cannot read the root
 * loader's data server-side), so the profile is fetched here again - same endpoint, same session,
 * cheap. Every per-workspace read is `allSettled`: a workspace whose reads fail contributes
 * zeros and flips `degraded`, it never blanks the page.
 *
 * "Today" is the server's calendar day (the same choice Activity's loader makes) - the request
 * carries no client time zone.
 */

export const RECENT_RUNS_LIMIT = 5;
export const ATTENTION_LIMIT = 5;
const SCHEDULES_LIMIT = 100;

export interface HomeRunCounts {
  all: number;
  succeeded: number;
  failed: number;
  running: number;
  waiting: number;
}

export interface HomeRun {
  id: string;
  workflowName: string;
  workflowRef: string;
  workspace: string;
  workspaceDisplayName: string;
  status: RunStatus;
  trigger: string;
  duration: number;
  creationDate: string;
}

export interface HomeAction extends Action {
  workspace: string;
  workspaceDisplayName: string;
}

export interface HomeSchedule {
  id: string;
  name: string;
  workspace: string;
  workspaceDisplayName: string;
  nextScheduleDate: string;
}

export interface HomeWorkspaceStats {
  runsToday: HomeRunCounts;
  lastRun: HomeRun | null;
  schedules: number;
}

export interface HomeLoaderData {
  /** The server's calendar day, e.g. "Tuesday 29 September". */
  dateLabel: string;
  runsToday: HomeRunCounts;
  /** Submitted actions across every workspace, newest first, capped at ATTENTION_LIMIT. */
  attention: Array<HomeAction>;
  attentionTotal: number;
  attentionApprovals: number;
  attentionManual: number;
  /** Newest runs across every workspace, capped at RECENT_RUNS_LIMIT. */
  recentRuns: Array<HomeRun>;
  /** Runs ever, across every workspace - drives the getting-started steps. */
  totalRuns: number;
  nextSchedule: HomeSchedule | null;
  schedulesTotal: number;
  stats: Record<string, HomeWorkspaceStats>;
  /** True when any read failed; the page renders with zeros for that part. */
  degraded: boolean;
}

export const EMPTY_RUN_COUNTS: HomeRunCounts = { all: 0, succeeded: 0, failed: 0, running: 0, waiting: 0 };

type Settled<T> = { ok: true; data: T } | { ok: false };

async function settle<T>(promise: Promise<{ data: T }>): Promise<Settled<T>> {
  try {
    return { ok: true, data: (await promise).data };
  } catch (error) {
    return { ok: false };
  }
}

function toRunCounts(status: Record<string, number> | undefined): HomeRunCounts {
  return {
    all: status?.all ?? 0,
    succeeded: status?.succeeded ?? 0,
    failed: status?.failed ?? 0,
    running: status?.running ?? 0,
    waiting: status?.waiting ?? 0,
  };
}

function addRunCounts(a: HomeRunCounts, b: HomeRunCounts): HomeRunCounts {
  return {
    all: a.all + b.all,
    succeeded: a.succeeded + b.succeeded,
    failed: a.failed + b.failed,
    running: a.running + b.running,
    waiting: a.waiting + b.waiting,
  };
}

function toHomeRun(run: WorkflowRun, workspace: FlowWorkspaceSummary): HomeRun {
  return {
    id: run.id,
    workflowName: run.workflowName,
    workflowRef: run.workflowRef,
    workspace: workspace.name,
    workspaceDisplayName: workspace.displayName,
    status: run.status,
    trigger: run.trigger,
    duration: run.duration,
    creationDate: run.creationDate,
  };
}

const newestFirst = (a: { creationDate: string }, b: { creationDate: string }) =>
  new Date(b.creationDate).getTime() - new Date(a.creationDate).getTime();

export async function loader({ request }: { request: Request }): Promise<HomeLoaderData> {
  const api = serverFetch(request);
  const now = moment();

  let workspaces: Array<FlowWorkspaceSummary> = [];
  let degraded = false;
  const profile = await settle(api.get<FlowUser>(serviceUrl.getUserProfile()));
  if (profile.ok) {
    workspaces = profile.data.teams ?? [];
  } else {
    degraded = true;
  }

  const countQuery = queryString.stringify(
    { fromDate: now.clone().startOf("day").valueOf(), toDate: now.clone().endOf("day").valueOf() },
    queryStringOptions,
  );
  const runsQuery = queryString.stringify({ limit: RECENT_RUNS_LIMIT, order: "DESC" }, queryStringOptions);
  const actionsQuery = queryString.stringify(
    { statuses: ["submitted"], limit: ATTENTION_LIMIT, order: "DESC" },
    queryStringOptions,
  );
  const schedulesQuery = queryString.stringify({ statuses: ["active"], limit: SCHEDULES_LIMIT }, queryStringOptions);

  // One wave: every workspace's four reads start together.
  const perWorkspace = await Promise.all(
    workspaces.map(async (workspace) => {
      const arg = { workspace: workspace.name };
      const [count, runs, actions, schedules] = await Promise.all([
        settle(api.get<{ status: Record<string, number> }>(serviceUrl.workspace.workflowrun.getWorkflowRunCount({ ...arg, query: countQuery }))),
        settle(api.get<PaginatedResponse<WorkflowRun>>(serviceUrl.workspace.workflowrun.getWorkflowRuns({ ...arg, query: runsQuery }))),
        settle(api.get<PaginatedResponse<Action>>(serviceUrl.workspace.action.getActions({ ...arg, query: actionsQuery }))),
        settle(api.get<PaginatedResponse<Schedule>>(serviceUrl.workspace.schedule.getSchedules({ ...arg, query: schedulesQuery }))),
      ]);
      return { workspace, count, runs, actions, schedules };
    }),
  );

  let runsToday = EMPTY_RUN_COUNTS;
  let recentRuns: Array<HomeRun> = [];
  let totalRuns = 0;
  let attention: Array<HomeAction> = [];
  let attentionTotal = 0;
  let nextSchedule: HomeSchedule | null = null;
  let schedulesTotal = 0;
  const stats: Record<string, HomeWorkspaceStats> = {};

  for (const { workspace, count, runs, actions, schedules } of perWorkspace) {
    const counts = count.ok ? toRunCounts(count.data?.status) : EMPTY_RUN_COUNTS;
    runsToday = addRunCounts(runsToday, counts);

    const workspaceRuns = runs.ok ? (runs.data?.content ?? []).map((run) => toHomeRun(run, workspace)) : [];
    recentRuns = recentRuns.concat(workspaceRuns);
    totalRuns += runs.ok ? runs.data?.totalElements ?? 0 : 0;

    if (actions.ok) {
      attention = attention.concat(
        (actions.data?.content ?? []).map((action) => ({
          ...action,
          workspace: workspace.name,
          workspaceDisplayName: workspace.displayName,
        })),
      );
      attentionTotal += actions.data?.totalElements ?? 0;
    }

    let scheduleCount = 0;
    if (schedules.ok) {
      scheduleCount = schedules.data?.totalElements ?? 0;
      for (const schedule of schedules.data?.content ?? []) {
        if (!schedule.nextScheduleDate) {
          continue;
        }
        const candidate: HomeSchedule = {
          id: schedule.id,
          name: schedule.name,
          workspace: workspace.name,
          workspaceDisplayName: workspace.displayName,
          nextScheduleDate: schedule.nextScheduleDate,
        };
        if (!nextSchedule || new Date(candidate.nextScheduleDate) < new Date(nextSchedule.nextScheduleDate)) {
          nextSchedule = candidate;
        }
      }
    }
    schedulesTotal += scheduleCount;

    degraded = degraded || !count.ok || !runs.ok || !actions.ok || !schedules.ok;
    stats[workspace.name] = {
      runsToday: counts,
      lastRun: workspaceRuns.slice().sort(newestFirst)[0] ?? null,
      schedules: scheduleCount,
    };
  }

  recentRuns = recentRuns.sort(newestFirst).slice(0, RECENT_RUNS_LIMIT);
  attention = attention.sort(newestFirst).slice(0, ATTENTION_LIMIT);

  return {
    dateLabel: now.format("dddd D MMMM"),
    runsToday,
    attention,
    attentionTotal,
    // The split is counted from the page returned, so it is exact whenever the total fits the
    // page and a lower bound otherwise - the total itself is always exact.
    attentionApprovals: attention.filter((action) => action.type === "approval").length,
    attentionManual: attention.filter((action) => action.type === "manual").length,
    recentRuns,
    totalRuns,
    nextSchedule,
    schedulesTotal,
    stats,
    degraded,
  };
}
