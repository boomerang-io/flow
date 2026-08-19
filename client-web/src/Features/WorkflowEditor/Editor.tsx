import React, { useCallback, useMemo, useRef, useState } from "react";
import { Loading, Error, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { AxiosResponse } from "axios";
import type { FormikProps } from "formik";
import queryString from "query-string";
import { useMutation, useQueryClient, UseMutationResult } from "react-query";
import { Route, Routes, useBlocker, useLocation, useParams } from "react-router-dom";
import type { ReactFlowInstance } from "reactflow";
import { useImmerReducer } from "use-immer";
import { useWorkspaceContext, useQuery } from "Hooks";
import { EditorContextProvider } from "State/context";
import { RevisionActionTypes, revisionReducer, initRevisionReducerState } from "State/reducers/workflowRevision";
import { WorkflowEngineMode, WorkspaceConfigType } from "Constants";
import { WorkflowView } from "Constants";
import { serviceUrl, resolver } from "Config/servicesConfig";
import {
  ChangeLog as ChangeLogType,
  ConfigureWorkflowFormValues,
  DataDrivenInput,
  PaginatedTaskResponse,
  PaginatedWorkflowResponse,
  Task,
  Workflow,
  WorkflowCanvas,
  FlowWorkspace,
} from "Types";
import ChangeLog from "./ChangeLog";
import Configure from "./Configure";
import Designer from "./Designer";
import Header from "./Header";
import Parameters from "./Parameters";
import Schedule from "./Schedule";

const CREATEABLE_PATHS = [
  "canvas",
  "parameters",
  "configure",
  "general",
  "triggers",
  "run",
  "workspaces",
  "parameters",
  "tokens",
];

export default function EditorContainer() {
  const { workspace }: { workspace: FlowWorkspace } = useWorkspaceContext();
  // Init revision number state is held here so we can easily refect the data on change via react-query

  const [revisionNumber, setRevisionNumber] = useState<string | number>("");
  const rawParams = useParams<{ workflow: string }>();
  const params = { workflow: rawParams.workflow ?? "" };

  const getWorkflowsUrl = serviceUrl.workspace.workflow.getWorkflows({ workspace: workspace.name });
  const getChangelogUrl = serviceUrl.workspace.workflow.getWorkflowChangelog({ workspace: workspace.name, workflow: params.workflow });
  const getWorkflowUrl = serviceUrl.workspace.workflow.getWorkflowCompose({
    workspace: workspace.name,
    workflow: params.workflow,
    version: revisionNumber,
  });

  const getTasksUrl = serviceUrl.task.queryTasks({
    query: queryString.stringify({ statuses: "active" }),
  });
  const getWorkspaceTasksUrl = serviceUrl.workspace.task.queryTasks({
    query: queryString.stringify({ statuses: "active" }),
    workspace: workspace.name,
  });

  const getAvailableParametersUrl = serviceUrl.workspace.workflow.getAvailableParameters({
    workspace: workspace.name,
    workflow: params.workflow,
  });

  /**
   * Queries
   */
  const workflowQuery = useQuery<WorkflowCanvas>(getWorkflowUrl);
  const workflowsQuery = useQuery<PaginatedWorkflowResponse>(getWorkflowsUrl);
  const changeLogQuery = useQuery<ChangeLogType>(getChangelogUrl);
  const availableParametersQuery = useQuery<Array<string>>(getAvailableParametersUrl);

  const tasksQuery = useQuery<PaginatedTaskResponse>(getTasksUrl, {
    refetchOnWindowFocus: false,
  });
  const workspaceTasksQuery = useQuery<PaginatedTaskResponse>(getWorkspaceTasksUrl, {
    refetchOnWindowFocus: false,
  });

  /**
   * Mutations
   */
  const revisionMutator = useMutation(resolver.putApplyWorkflowCompose);

  if (
    workflowQuery.isLoading ||
    workflowsQuery.isLoading ||
    changeLogQuery.isLoading ||
    tasksQuery.isLoading ||
    workspaceTasksQuery.isLoading ||
    availableParametersQuery.isLoading
  ) {
    return <Loading />;
  }

  if (
    workflowQuery.error ||
    workflowsQuery.error ||
    changeLogQuery.error ||
    tasksQuery.error ||
    workspaceTasksQuery.error ||
    availableParametersQuery.error
  ) {
    return <Error />;
  }

  if (
    workflowQuery.data &&
    workflowsQuery.data &&
    changeLogQuery.data &&
    tasksQuery.data &&
    workspaceTasksQuery.data &&
    availableParametersQuery.data
  ) {
    const taskList = [...tasksQuery.data.content, ...prefixWorkspaceTask(workspaceTasksQuery.data.content, workspace)];
    return (
      <EditorStateContainer
        availableParametersQueryData={availableParametersQuery.data}
        parametersUrl={getAvailableParametersUrl}
        changeLogData={changeLogQuery.data}
        revisionNumber={revisionNumber}
        revisionMutator={revisionMutator}
        workflowQueryUrl={getWorkflowUrl}
        workflowQueryData={workflowQuery.data}
        workflowsQueryData={workflowsQuery.data}
        setRevisionNumber={setRevisionNumber}
        taskList={taskList}
        workflowRef={params.workflow}
        key={revisionNumber}
        workspace={workspace}
      />
    );
  }

  return null;
}

interface EditorStateContainerProps {
  availableParametersQueryData: Array<string>;
  changeLogData: ChangeLogType;
  parametersUrl: string;
  revisionMutator: UseMutationResult<
    AxiosResponse<Workflow, any>,
    unknown,
    { workspace: any; workflow: any; body: any },
    unknown
  >;
  revisionNumber: string | number;
  setRevisionNumber: React.Dispatch<React.SetStateAction<string | number>>;
  taskList: Array<Task>;
  workflowQueryUrl: string;
  workflowQueryData: WorkflowCanvas;
  workflowsQueryData: PaginatedWorkflowResponse;
  workflowRef: string;
  workspace: FlowWorkspace;
}

/**
 * Workflow Manager responsible for holding state of summary and revision
 * Make function calls to mutate server data
 */
const EditorStateContainer: React.FC<EditorStateContainerProps> = ({
  availableParametersQueryData,
  changeLogData,
  parametersUrl,
  revisionMutator,
  setRevisionNumber,
  taskList,
  workflowQueryUrl,
  workflowQueryData,
  workflowsQueryData,
  workflowRef,
  workspace,
}) => {
  const location = useLocation();
  const queryClient = useQueryClient();

  const [revisionState, revisionDispatch] = useImmerReducer(
    revisionReducer,
    initRevisionReducerState(workflowQueryData),
  );

  const [reactFlowInstance, setReactFlowInstance] = React.useState<ReactFlowInstance | null>(null);
  const [availableParameters, setAvailableParameters] = React.useState(availableParametersQueryData);
  const settingsRef = useRef<FormikProps<any> | null>(null);

  const handleCreateRevision = async ({ reason = "Update workflow", callback }: any) => {
    const configureValues = settingsRef?.current?.values ?? {};
    const formattedConfigureValue = formatConfigureValues(configureValues);

    if (reactFlowInstance) {
      const workfowDagObject = reactFlowInstance.toObject();
      const revision = {
        ...revisionState,
        ...workfowDagObject,
        ...formattedConfigureValue,
        changelog: { reason },
      };

      try {
        const { data } = await revisionMutator.mutateAsync({ workspace: workspace.name, workflow: workflowRef, body: revision });
        notify(
          <ToastNotification kind="success" title="Create Version" subtitle="Successfully created workflow version" />,
        );
        if (typeof callback === "function") {
          callback();
        }
        revisionDispatch({ type: RevisionActionTypes.Set, data });
        setRevisionNumber(data.version);
        queryClient.invalidateQueries(parametersUrl);
        queryClient.invalidateQueries(workflowQueryUrl);
      } catch (err) {
        notify(
          <ToastNotification kind="error" title="Something's Wrong" subtitle={`Failed to create workflow version`} />,
        );
      }
    }
  };

  const handleUpdateNotes = useCallback(
    (markdown: string) => {
      revisionDispatch({
        type: RevisionActionTypes.UpdateNotes,
        data: { markdown },
      });
    },
    [revisionDispatch],
  );

  /**
   * Welp this is more complicated than I hoped it would (has?) to be
   * We are making client side updates to the parameters available to a Workflow
   * parameters - defined at the Workflow-level in the Parameters tab
   * deletedParams - parameters that were removed
   * availableParameters - parameters supplied by its relationship to other entities
   *   - workspace
   *   - global
   *   - context
   * that get requested and made available to Workflow task configuration
   * Parameters are represented in two ways, "flat" and "layer"
   * e.g. Workflow "api-token" as workflow.params.api-token and param.api-token.
   * e.g. Workspace "api-token" as workspace.params.api-token and param.api-token.
   *
   * When a token of value `api-token` gets added we need to add both versions
   * When a token of value `api-token` gets deleted we need to delete the workflow
   * layer version AND check if there is a matching higher layer one. If there
   * IS NOT then we need to delete the flat token as well
   *
   * All of this is because params are versioned along w/ the Workflow so when we edit things
   * client side we need to propogate those changes within the Editor
   */
  const handleUpdateParams = useCallback(
    (parameters: Array<DataDrivenInput>, deletedParameters: Array<DataDrivenInput>) => {
      revisionDispatch({
        type: RevisionActionTypes.UpdateConfig,
        data: { parameters },
      });

      const newAvailableParameters = [...availableParameters];

      for (let parameter of parameters) {
        newAvailableParameters.push(`workflow.params.${parameter.key}`, `params.${parameter.key}`);
      }

      const availableParameterSet = new Set(newAvailableParameters);
      for (let deletedParameter of deletedParameters) {
        const deletedWorkflowParamKey = `workflow.params.${deletedParameter.key}`;
        const deletedFlatParamKey = `params.${deletedParameter.key}`;
        const higherLayerParamList = [
          `context.params.${deletedParameter.key}`,
          `global.params.${deletedParameter.key}`,
          `workspace.params.${deletedParameter.key}`,
        ];

        availableParameterSet.delete(deletedWorkflowParamKey);
        const hasHigherLayerParam =
          availableParameterSet.has(higherLayerParamList[0]) ||
          availableParameterSet.has(higherLayerParamList[1]) ||
          availableParameterSet.has(higherLayerParamList[2]);

        if (!hasHigherLayerParam) {
          availableParameterSet.delete(deletedFlatParamKey);
        }
      }

      setAvailableParameters(Array.from(availableParameterSet));
    },
    [revisionDispatch, availableParameters, setAvailableParameters],
  );

  /**
   *  Simply update the parent state to use a different revision to fetch it w/ react-query
   * @param {string} revisionNumber
   */
  const handleChangeRevision = (revisionNumber: string) => {
    return setRevisionNumber(revisionNumber);
  };

  const revisionCount = changeLogData.length;
  const { markdown, version } = revisionState;
  const mode = version === revisionCount ? WorkflowEngineMode.Edit : WorkflowEngineMode.View;
  const store = useMemo(() => {
    return {
      availableParameters,
      mode,
      revisionDispatch,
      revisionState,
      workflowsQueryData,
    };
  }, [availableParameters, mode, revisionDispatch, revisionState, workflowsQueryData]);

  // Same in-app "leave without saving" guard as before, ported from v5's <Prompt> to v6/v7's
  // useBlocker (requires the data router set up in Root.tsx). Blocks navigation away from
  // this workflow while there are unsaved changes; switching tabs within the same workflow
  // (nextLocation.pathname still includes workflowRef) is allowed through unprompted.
  const blocker = useBlocker(
    ({ nextLocation }) => Boolean(revisionState.hasUnsavedUpdates) && !nextLocation.pathname.includes(workflowRef),
  );

  React.useEffect(() => {
    if (blocker.state === "blocked") {
      if (window.confirm("Are you sure? You have unsaved changes to your workflow that will be lost.")) {
        blocker.proceed();
      } else {
        blocker.reset();
      }
    }
  }, [blocker]);

  return (
    // Must create context to share state w/ nodes that are created by the DAG engine
    <EditorContextProvider value={store}>
      <>
        <Header
          changeLog={changeLogData}
          changeRevision={handleChangeRevision}
          createRevision={handleCreateRevision}
          canCreateNewVersion={CREATEABLE_PATHS.includes(location.pathname.split("/").pop() || "")}
          revisionState={revisionState}
          viewType={WorkflowView.Workflow}
          revisionCount={revisionCount}
          revisionMutator={revisionMutator}
        />
        <Routes>
          <Route path="canvas" element={null} />
          <Route path="parameters" element={<Parameters workflow={revisionState} handleUpdateParams={handleUpdateParams} />} />
          <Route path="schedule" element={<Schedule workflow={revisionState} />} />
          <Route path="changelog" element={<ChangeLog changeLogData={changeLogData} />} />
        </Routes>
        {
          // Always render parent Configure component so state isn't lost when switching tabs
          // It is responsible for rendering its children, but Formik form management is always mounted
          <>
            <Designer
              notes={markdown}
              reactFlowInstance={reactFlowInstance}
              setReactFlowInstance={setReactFlowInstance}
              tasks={taskList}
              updateNotes={handleUpdateNotes}
              workflow={revisionState}
            />
            <Configure workflow={revisionState} settingsRef={settingsRef} />
          </>
        }
      </>
    </EditorContextProvider>
  );
};

/**
 * Format the form configure values into something that the API accepts
 * Update the `workspaces` and `labels` to be in the right format
 */
function formatConfigureValues(configureValues: ConfigureWorkflowFormValues): Partial<WorkflowCanvas> {
  const optionalConfigureValues: Partial<ConfigureWorkflowFormValues> = configureValues;

  // Format labels
  const labelsKVObject = configureValues.labels.reduce(
    (accum, current) => {
      accum[current.key] = current.value;
      return accum;
    },
    {} as Record<string, string>,
  );

  // Format workspaces
  const workflowStorageConfig = configureValues.storage?.workflow?.enabled
    ? {
        name: WorkspaceConfigType.Workflow,
        type: WorkspaceConfigType.Workflow,
        optional: false,
        spec: { size: configureValues.storage.workflow.size, mountPath: configureValues.storage.workflow.mountPath },
      }
    : null;

  const workflowRunStorageConfig = configureValues.storage?.workflowrun?.enabled
    ? {
        name: WorkspaceConfigType.WorflowRun,
        type: WorkspaceConfigType.WorflowRun,
        optional: false,
        spec: { size: configureValues.storage.workflow.size, mountPath: configureValues.storage.workflow.mountPath },
      }
    : null;

  const workspaces = [workflowStorageConfig, workflowRunStorageConfig].filter(Boolean) as WorkflowCanvas["workspaces"];

  delete optionalConfigureValues["storage"];

  const formattedWorkflowConfig: Partial<WorkflowCanvas> = {
    ...optionalConfigureValues,
    workspaces,
    labels: labelsKVObject,
    timeout: configureValues.timeout ?? undefined,
    retries: configureValues.retries ?? undefined,
  };

  return formattedWorkflowConfig;
}

// TODO make shared util
function prefixWorkspaceTask(taskList: Array<Task>, workspace: FlowWorkspace) {
  return taskList.map((task) => {
    return {
      ...task,
      name: `${workspace.name}/${task.name}`,
      displayName: `${workspace.displayName} - ${task.displayName}`,
      // Distinguishes workspace-scoped tasks from global ones in the designer's task list (Task.tsx).
      scope: "workspace",
    };
  });
}
