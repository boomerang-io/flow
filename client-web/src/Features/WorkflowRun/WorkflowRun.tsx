import React from "react";
import { ErrorMessage, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useLoaderData, useNavigate, useRevalidator } from "react-router-dom";
import { Box } from "reflexbox";
import ReactFlow from "Features/Reactflow";
import { useWorkspaceContext, RunContextProvider } from "State/context";
import { groupTasksByName } from "Utils";
import { HttpMethod, NodeType, WorkflowEngineMode } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import {
  Action,
  FlowWorkspace,
  RunPhase,
  RunStatus,
  Task,
  TaskRun,
  WorkflowCanvas,
  WorkflowReactFlowInstance,
  WorkflowRun,
} from "Types";
import { actionError, type ActionError } from "Utils/actionResult";
import RunHeader from "./RunHeader";
import RunTaskLog from "./TaskRunList";
import WorkflowActions from "./WorkflowActions";
import styles from "./WorkflowRun.module.scss";

// Route module for AppPath.Run ("/:workspace/activity/:runId") - see app/routes/run.tsx, which
// re-exports the `loader`/`action` below. Follows the GlobalParameters/WorkspaceTasks reference
// conversions: reads move to a server `loader` using serverFetch(request), writes move to a
// route `action` driven by useFetcher, and react-query leaves this feature entirely.
//
// `params.workspace` is the same value app/routes/workspaceLayout.tsx's loader resolves its
// workspace from (params.workspace -> serviceUrl.resourceWorkspace), so it is interchangeable
// with the `workspace.name` the old browser-side queries used - but it is available to the
// loader, which useWorkspaceContext() is not.

const POLL_INTERVAL_MS = 5000;

// A run in one of these statuses will not change again on its own, so polling stops. Anything
// that *can* still change it from here is a user action (finalize, retry), and those submit
// through the fetcher below, which revalidates on completion.
const TERMINAL_STATUSES = [
  RunStatus.Succeeded,
  RunStatus.Failed,
  RunStatus.Invalid,
  RunStatus.Cancelled,
  RunStatus.Skipped,
  RunStatus.TimedOut,
];

const ACTION_TASK_TYPES: string[] = [NodeType.Approval, NodeType.Manual];

export type LoaderData = {
  workflowRun: WorkflowRun | null;
  workflow: WorkflowCanvas | null;
  tasks: Array<Task>;
  workspaceTasks: Array<Task>;
  // Keyed by TaskRun id. See resolveActions() below.
  actions: Record<string, Action>;
  errorLoading: boolean;
};

const EMPTY_LOADER_DATA: LoaderData = {
  workflowRun: null,
  workflow: null,
  tasks: [],
  workspaceTasks: [],
  actions: {},
  errorLoading: true,
};

/*
 * A TaskRun for an `approval`/`manual` task carries only an `actionRef` result
 * (TaskExecutionService writes it at creation); the approver names, verdicts, comments and
 * dates live solely on the Action record. There is no route to fetch Actions by
 * taskRunRef/workflowRunRef - `/action/query` filters only on types/statuses/workflows/dates -
 * so each one is resolved by id here, in the loader, rather than by each list item opening its
 * own request. Restricted to completed tasks because that is exactly where RunTaskItem renders
 * them ("View Action"), which keeps this at zero extra requests for the common run and avoids
 * re-fetching a still-`waiting` approval on every poll tick.
 */
async function resolveActions(
  api: ReturnType<typeof serverFetch>,
  workspace: string,
  tasks: Array<TaskRun> | undefined,
): Promise<Record<string, Action>> {
  const pending = (tasks ?? [])
    .filter((taskRun) => ACTION_TASK_TYPES.includes(taskRun.type) && taskRun.phase === RunPhase.Completed)
    .map((taskRun) => ({
      taskRunId: taskRun.id,
      actionRef: taskRun.results?.find((result) => result.name === "actionRef")?.value,
    }))
    .filter((entry): entry is { taskRunId: string; actionRef: string } => Boolean(entry.actionRef));

  if (pending.length === 0) {
    return {};
  }

  const resolved = await Promise.all(
    pending.map(async ({ taskRunId, actionRef }) => {
      try {
        const response = await api.get(serviceUrl.workspace.action.getAction({ workspace, id: actionRef }));
        return [taskRunId, response.data as Action] as const;
      } catch (error) {
        // One unreadable Action must not blank the whole run view - the modal falls back to
        // "no approvals recorded" for just that task.
        return null;
      }
    }),
  );

  return Object.fromEntries(resolved.filter((entry): entry is readonly [string, Action] => entry !== null));
}

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string; runId?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const runId = String(params.runId);
  const api = serverFetch(request);
  const activeTasksQuery = queryString.stringify({ statuses: "active" });

  let workflowRun: WorkflowRun;
  let tasks: Array<Task>;
  let workspaceTasks: Array<Task>;
  try {
    // The run and both task catalogues are independent - only the compose call below depends on
    // the run (it needs its workflow name + version), so it is the one sequential step.
    const [runResponse, tasksResponse, workspaceTasksResponse] = await Promise.all([
      api.get(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace, id: runId })),
      api.get(serviceUrl.task.queryTasks({ query: activeTasksQuery })),
      api.get(serviceUrl.workspace.task.queryTasks({ workspace, query: activeTasksQuery })),
    ]);
    workflowRun = runResponse.data;
    tasks = tasksResponse.data.content;
    workspaceTasks = workspaceTasksResponse.data.content;
  } catch (error) {
    // Mirrors the previous `executionQuery.error` branch: resolve with a flag so the route still
    // renders its own ErrorMessage rather than throwing into the router's errorElement.
    return EMPTY_LOADER_DATA;
  }

  let workflow: WorkflowCanvas | null = null;
  let errorLoading = false;
  try {
    const workflowResponse = await api.get(
      serviceUrl.workspace.workflow.getWorkflowComposeRun({
        workspace,
        workflow: workflowRun.workflowName,
        version: workflowRun.workflowVersion,
      }),
    );
    workflow = workflowResponse.data;
  } catch (error) {
    errorLoading = true;
  }

  const actions = await resolveActions(api, workspace, workflowRun.tasks);

  return { workflowRun, workflow, tasks, workspaceTasks, actions, errorLoading };
}

export type RunActionIntent = "retry" | "cancel" | "start" | "pause" | "resume" | "finalize" | "action";

export type ActionResult = { intent: RunActionIntent } | ({ intent: RunActionIntent } & ActionError);

const RUN_INTENT_REQUESTS: Record<
  Exclude<RunActionIntent, "action">,
  { url: (args: { workspace: string; id: string }) => string; method: string }
> = {
  retry: { url: serviceUrl.workspace.workflowrun.putRetryWorkflow, method: HttpMethod.Put },
  cancel: { url: serviceUrl.workspace.workflowrun.deleteCancelWorkflow, method: HttpMethod.Delete },
  start: { url: serviceUrl.workspace.workflowrun.putStartWorkflow, method: HttpMethod.Put },
  pause: { url: serviceUrl.workspace.workflowrun.putPauseWorkflow, method: HttpMethod.Put },
  resume: { url: serviceUrl.workspace.workflowrun.putResumeWorkflow, method: HttpMethod.Put },
  finalize: { url: serviceUrl.workspace.workflowrun.putFinalizeWorkflow, method: HttpMethod.Put },
};

/*
 * Every write this route makes: the six run lifecycle transitions driven from RunHeader, plus
 * `action` (the approval/manual submission from the two task modals). A fetcher submission to a
 * route action revalidates the route's loader on completion, which is what replaces the
 * queryClient.invalidateQueries(getWorkflowRun) calls these mutations used to make.
 */
export async function action({
  params,
  request,
}: {
  params: { workspace?: string; runId?: string };
  request: Request;
}) {
  const workspace = String(params.workspace);
  const id = String(params.runId);
  const formData = await request.formData();
  const intent = String(formData.get("intent")) as RunActionIntent;
  const api = serverFetch(request);

  if (intent === "action") {
    // The approval/manual PUT takes a list of decisions; the modals submit exactly one.
    const body = [
      {
        id: String(formData.get("actionId")),
        approved: formData.get("approved") === "true",
        comments: String(formData.get("comments") ?? ""),
      },
    ];
    try {
      await api({ url: serviceUrl.workspace.action.putAction({ workspace }), data: body, method: HttpMethod.Put });
      return { intent };
    } catch (error) {
      return actionError({
        intent,
        error: formatErrorMessage({ error, defaultMessage: "Request to submit the action failed" }),
      });
    }
  }

  const requestConfig = RUN_INTENT_REQUESTS[intent as Exclude<RunActionIntent, "action">];
  if (!requestConfig) {
    return actionError({ intent, error: { title: "Something's wrong", message: "Unrecognised request" } });
  }

  try {
    await api({ url: requestConfig.url({ workspace, id }), method: requestConfig.method });
    return { intent };
  } catch (error) {
    return actionError({
      intent,
      error: formatErrorMessage({ error, defaultMessage: `Failed to ${intent} this run` }),
    });
  }
}

export default function WorkflowRunFeature() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const { workflowRun, workflow, tasks, workspaceTasks, actions, errorLoading } = useLoaderData() as LoaderData;
  const revalidator = useRevalidator();

  const isTerminal = workflowRun ? TERMINAL_STATUSES.includes(workflowRun.status) : true;

  /*
   * Live status. The loader supplies the initial data and useRevalidator re-runs it on an
   * interval, replacing react-query's `refetchInterval: 5000` - a route loader is the single
   * fetch path now, so a parallel client query would both duplicate it and desync the two.
   * The revalidator is read through a ref so the interval is created once per terminal-state
   * change rather than being torn down and restarted on every `revalidator.state` transition
   * (which, at a 5s period, could starve the poll). A tick while a revalidation is still in
   * flight is skipped rather than stacked.
   */
  const revalidatorRef = React.useRef(revalidator);
  revalidatorRef.current = revalidator;

  React.useEffect(() => {
    if (isTerminal) {
      return;
    }
    const intervalId = setInterval(() => {
      if (revalidatorRef.current.state === "idle") {
        revalidatorRef.current.revalidate();
      }
    }, POLL_INTERVAL_MS);
    return () => clearInterval(intervalId);
  }, [isTerminal]);

  function executionViewRedirect({ workflowRunRef }: { workflowRunRef: string }) {
    // No cache to invalidate any more - navigating to another runId re-runs this loader.
    navigate(appLink.execution({ workspace: workspace.name, runId: workflowRunRef }));
  }

  if (errorLoading || !workflowRun || !workflow) {
    return (
      <Box mt="5rem">
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <ErrorMessage />
      </Box>
    );
  }

  const groupedTasks = groupTasksByName([...tasks, ...prefixWorkspaceTask(workspaceTasks, workspace)]);

  return (
    <RunContextProvider value={{ workflow, workflowRun }}>
      <Main
        tasks={groupedTasks}
        workflow={workflow}
        workflowRun={workflowRun}
        actions={actions}
        version={workflowRun.workflowVersion}
        executionViewRedirect={executionViewRedirect}
      />
    </RunContextProvider>
  );
}

type MainProps = {
  tasks: Record<string, Array<Task>>;
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
  actions: Record<string, Action>;
  version: number;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function Main(props: MainProps) {
  const { workflow, workflowRun, actions, version, executionViewRedirect } = props;
  const [reactFlowInstance, setReactFlowInstance] = React.useState<WorkflowReactFlowInstance | null>(null);

  const { status, tasks: runTasks } = workflowRun;
  const hasFinished = TERMINAL_STATUSES.includes(status);
  const hasStarted = runTasks ? Boolean(runTasks.find((step) => step.status !== RunStatus.NotStarted)) : false;

  const isDiagramLoaded = hasStarted || hasFinished;

  return (
    <div className={styles.container}>
      <Helmet>
        <title>{workflow ? `${workflow.name} - Activity` : `Activity`}</title>
      </Helmet>
      <RunHeader
        workflow={workflow}
        workflowRun={workflowRun}
        version={version}
        executionViewRedirect={executionViewRedirect}
      />
      <section aria-label="Executions" className={styles.executionResultContainer}>
        <RunTaskLog workflowRun={workflowRun} actions={actions} executionViewRedirect={executionViewRedirect} />
        <div className={styles.executionDesignerContainer}>
          <div className={styles.executionWorkflowActions}>
            <WorkflowActions workflow={workflow} />
          </div>
          <ReactFlow
            mode={WorkflowEngineMode.Run}
            nodes={workflow.nodes}
            edges={workflow.edges}
            reactFlowInstance={reactFlowInstance}
            setReactFlowInstance={setReactFlowInstance}
            tasks={props.tasks}
          />
          {!isDiagramLoaded && (
            <div className={styles.diagramLoading}>
              <Loading withOverlay={false} />
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function prefixWorkspaceTask(taskList: Array<Task>, workspace: FlowWorkspace) {
  return taskList.map((task) => {
    return {
      ...task,
      name: `${workspace.name}/${task.name}`,
      displayName: `${workspace.displayName} - ${task.displayName}`,
    };
  });
}
