import React from "react";
import { ErrorMessage, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useQueryClient } from "react-query";
import { useHistory, useParams } from "react-router-dom";
import type { ReactFlowInstance } from "reactflow";
import { Box } from "reflexbox";
import ReactFlow from "Features/Reactflow";
import { useQuery } from "Hooks";
import { useTeamContext, RunContextProvider } from "State/context";
import { groupTasksByName } from "Utils";
import { WorkflowEngineMode } from "Constants";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { FlowTeam, PaginatedTaskResponse, RunStatus, Task, WorkflowCanvas, WorkflowRun } from "Types";
import RunHeader from "./RunHeader";
import RunTaskLog from "./TaskRunList";
import WorkflowActions from "./WorkflowActions";
import styles from "./WorkflowRun.module.scss";

export default function WorkflowRunFeature() {
  const { team } = useTeamContext();
  const queryClient = useQueryClient();
  const history = useHistory();
  const params = useParams<{ team: string; workflow: string; runId: string }>();
  const getTasksUrl = serviceUrl.task.queryTasks({
    query: queryString.stringify({ statuses: "active" }),
  });
  const getTeamTasksUrl = serviceUrl.team.task.queryTasks({
    query: queryString.stringify({ statuses: "active" }),
    team: team.name,
  });
  const getExecutionUrl = serviceUrl.team.workflowrun.getWorkflowRun({ team: team.name, id: params.runId });

  function executionViewRedirect({ workflowRunRef }: { workflowRunRef: string }) {
    queryClient.invalidateQueries(getExecutionUrl);
    history.push(
      appLink.execution({
        team: team.name,
        runId: workflowRunRef,
      }),
    );
  }

  /**
   * Queries
   */
  const executionQuery = useQuery<WorkflowRun>(getExecutionUrl, {
    refetchInterval: 5000,
  });

  const tasksQuery = useQuery<PaginatedTaskResponse>(getTasksUrl);
  const teamTasksQuery = useQuery<PaginatedTaskResponse>(getTeamTasksUrl);

  if (tasksQuery.isLoading || teamTasksQuery.isLoading || executionQuery.isLoading) {
    return (
      <>
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <Loading />
      </>
    );
  }

  if (tasksQuery.error || teamTasksQuery.error || executionQuery.error) {
    return (
      <Box mt="5rem">
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <ErrorMessage />
      </Box>
    );
  }

  if (tasksQuery.data && teamTasksQuery.data && executionQuery.data) {
    return (
      <RevisionContainer
        team={team.name}
        workflowRun={executionQuery.data}
        tasksData={[...tasksQuery.data.content, ...prefixTeamTask(teamTasksQuery.data.content, team)]}
        workflowRef={executionQuery.data.workflowName}
        executionViewRedirect={executionViewRedirect}
      />
    );
  }

  return null;
}

type RevisionProps = {
  team: string;
  tasksData: Task[];
  workflowRef: string;
  workflowRun: WorkflowRun;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function RevisionContainer({ team, workflowRun, tasksData, workflowRef, executionViewRedirect }: RevisionProps) {
  const version = workflowRun.workflowVersion;
  const getWorkflowUrl = serviceUrl.team.workflow.getWorkflowComposeRun({
    team: team,
    workflow: workflowRef,
    version,
  });

  const groupedTasks = groupTasksByName(tasksData);
  const workflowQuery = useQuery<WorkflowCanvas>(getWorkflowUrl);

  if (workflowQuery.isLoading) {
    return (
      <>
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <Loading />
      </>
    );
  }

  if (workflowQuery.isError) {
    return (
      <Box mt="5rem">
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <ErrorMessage />
      </Box>
    );
  }

  if (workflowQuery.data) {
    return (
      <RunContextProvider
        value={{
          workflow: workflowQuery.data,
          workflowRun: workflowRun,
        }}
      >
        <Main
          tasks={groupedTasks}
          workflow={workflowQuery.data}
          workflowRun={workflowRun}
          version={version}
          executionViewRedirect={executionViewRedirect}
        />
      </RunContextProvider>
    );
  }

  return null;
}

type MainProps = {
  tasks: Record<string, Array<Task>>;
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
  version: number;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function Main(props: MainProps) {
  const { workflow, workflowRun, version, executionViewRedirect } = props;
  const [reactFlowInstance, setReactFlowInstance] = React.useState<ReactFlowInstance | null>(null);
  let hasFinished = false;
  let hasStarted = false;

  const { status, tasks: runTasks } = workflowRun;
  hasFinished = [
    RunStatus.Invalid,
    RunStatus.Failed,
    RunStatus.TimedOut,
    RunStatus.Skipped,
    RunStatus.Succeeded,
  ].includes(status);
  hasStarted = runTasks ? Boolean(runTasks.find((step) => step.status !== RunStatus.NotStarted)) : false;

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
        <RunTaskLog workflowRun={workflowRun} executionViewRedirect={executionViewRedirect} />
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

function prefixTeamTask(taskList: Array<Task>, team: FlowTeam) {
  return taskList.map((task) => {
    return {
      ...task,
      name: `${team.name}/${task.name}`,
      displayName: `${team.displayName} - ${task.displayName}`,
    };
  });
}
