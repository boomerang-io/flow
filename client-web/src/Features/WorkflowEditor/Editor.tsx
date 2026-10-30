import { gbToQuantity } from "Utils/storageQuantity";
import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { Loading, Error, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import type { FormikProps } from "formik";
import { Route, Routes, useBlocker, useFetcher, useLocation, useParams, useSearchParams } from "react-router-dom";
import { useImmerReducer } from "use-immer";
import { useWorkspaceContext } from "Hooks";
import { EditorContextProvider } from "State/context";
import { RevisionActionTypes, revisionReducer, initRevisionReducerState } from "State/reducers/workflowRevision";
import { WorkflowEngineMode, WorkflowView } from "Constants";
import {
  ChangeLog as ChangeLogType,
  ConfigureWorkflowFormValues,
  DataDrivenInput,
  PaginatedWorkflowResponse,
  Task,
  WorkflowCanvas,
  FlowWorkspace,
  WorkflowReactFlowInstance,
} from "Types";
import { groupTasksByName } from "Utils";
import { isActionError } from "Utils/actionResult";
import { isReadOnlyTasks } from "Utils/workflowTemplate";
import {
  missingWorkspaceParams,
  missingWorkspaceParamsMessage,
  type MissingWorkspaceParam,
} from "Utils/workspaceParamReferences";
import ChangeLog from "./ChangeLog";
import Configure from "./Configure";
import Designer from "./Designer";
import { useEditorRouteData } from "./editorRouteData";
import type { EditorActionResult } from "./editorRoute";
import Header from "./Header";
import Parameters from "./Parameters";
import Schedule from "./Schedule";

/*
 * The six useQuery calls this container used to make now live in the route's loader
 * (editorRoute.ts, attached in app/routes/editor.tsx) and arrive through useEditorRouteData();
 * the single useMutation is a useFetcher() submitting the "createRevision" intent to the same
 * route's action. `queryClient.invalidateQueries` is gone with them - the fetcher's own
 * completion revalidates the loader, which is what refreshes the workflow and its available
 * parameters after a new version is created.
 *
 * Type-only import of EditorActionResult from the Node-only editorRoute.ts: erased at compile,
 * so it never pulls Config/serverFetch into the browser bundle (same pattern as
 * TokenSection.tsx's `import type { TokenActionResult }`).
 */

const CREATEABLE_PATHS = [
  "canvas",
  "parameters",
  "configure",
  "general",
  "triggers",
  "run",
  "workspace",
  "artifacts",
  "parameters",
  "tokens",
];

export default function EditorContainer() {
  const { workspace }: { workspace: FlowWorkspace } = useWorkspaceContext();
  const rawParams = useParams<{ workflow: string }>();
  const params = { workflow: rawParams.workflow ?? "" };
  const editorData = useEditorRouteData();

  /*
   * The version being viewed was `useState` here, purely so react-query would refetch the compose
   * on change. A loader re-runs on URL change, not on setState, so it is a `?version=` search
   * param now - which also makes a specific version linkable. Absent means "latest", exactly as
   * the empty-string initial state did.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const revisionNumber = searchParams.get("version") ?? "";

  const changeVersion = useCallback(
    (version: string | number) => {
      setSearchParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.set("version", String(version));
          return next;
        },
        { replace: false },
      );
    },
    [setSearchParams],
  );

  // Undefined only when this component is rendered outside a route carrying the editor loader
  // (which the router never does in the app itself); the previous code showed the same spinner
  // while its queries were in flight.
  if (!editorData) {
    return <Loading />;
  }

  if (editorData.errorLoading) {
    return <Error />;
  }

  const { workflow, workflows, changeLog, availableParameters, tasks, workspaceTasks } = editorData;

  if (workflow && workflows && changeLog && availableParameters && tasks && workspaceTasks) {
    const taskList = [...tasks.content, ...prefixWorkspaceTask(workspaceTasks.content, workspace)];
    return (
      <EditorStateContainer
        availableParametersData={availableParameters}
        changeLogData={changeLog}
        changeVersion={changeVersion}
        taskList={taskList}
        workflowData={workflow}
        workflowsData={workflows}
        workflowRef={params.workflow}
        key={revisionNumber}
        workspace={workspace}
      />
    );
  }

  return null;
}

interface EditorStateContainerProps {
  availableParametersData: Array<string>;
  changeLogData: ChangeLogType;
  changeVersion: (version: string | number) => void;
  taskList: Array<Task>;
  workflowData: WorkflowCanvas;
  workflowsData: PaginatedWorkflowResponse;
  workflowRef: string;
  workspace: FlowWorkspace;
}

/**
 * Workflow Manager responsible for holding state of summary and revision
 * Make function calls to mutate server data
 */
const EditorStateContainer: React.FC<EditorStateContainerProps> = ({
  availableParametersData,
  changeLogData,
  changeVersion,
  taskList,
  workflowData,
  workflowsData,
  workflowRef,
}) => {
  const location = useLocation();
  const { workspace } = useWorkspaceContext();
  // No explicit action path: a bare useFetcher() resolves to the nearest matched route, which is
  // the editor route itself (app/routes/editor.tsx) - the same way the Configure > Tokens tab's
  // fetcher reaches the token half of that route's one action.
  const fetcher = useFetcher<EditorActionResult>();

  const [revisionState, revisionDispatch] = useImmerReducer(revisionReducer, initRevisionReducerState(workflowData));

  const [reactFlowInstance, setReactFlowInstance] = React.useState<WorkflowReactFlowInstance | null>(null);
  const [availableParameters, setAvailableParameters] = React.useState(availableParametersData);
  const settingsRef = useRef<FormikProps<any> | null>(null);
  /*
   * VersionCommentForm hands this component its own `closeModal` at submit time. The fetcher
   * settles asynchronously (its result arrives on a later render), so the callback is stashed
   * here and invoked from the effect below once the create actually succeeds - the same
   * "stay open, close only on success" behaviour the old `await mutateAsync(...)` chain had.
   * See GlobalParameters.tsx for the reference version of this.
   */
  const createRevisionCallbackRef = useRef<(() => void) | null>(null);
  // The workspace parameters the version being saved reads but the workspace does not provide; a warning once
  // the save succeeds, never a reason to refuse it.
  const missingWorkspaceParamsRef = useRef<Array<MissingWorkspaceParam>>([]);

  const readOnlyTasks = isReadOnlyTasks(revisionState, workspace.name);
  // Settings saved on a template-backed workflow, kept to fold into the editor state once the save lands.
  const savedSettingsRef = useRef<Partial<WorkflowCanvas> | null>(null);

  // A template (or a workflow that follows one) keeps only its settings: the apply body carries
  // no tasks or params, and always names the template so the server makes or keeps the link.
  const handleSaveSettings = () => {
    const configureValues = settingsRef?.current?.values;
    if (!configureValues) {
      return;
    }
    const { templateVersion, ...values } = configureValues;
    const formatted = formatConfigureValues(values);
    const templateName = revisionState.templateRef?.name ?? revisionState.name;
    const settings: Partial<WorkflowCanvas> = {
      name: revisionState.name,
      displayName: formatted.displayName,
      description: formatted.description,
      icon: formatted.icon,
      labels: formatted.labels,
      triggers: formatted.triggers,
      type: "workflow",
      templateRef: { name: templateName, ...(templateVersion ? { version: Number(templateVersion) } : {}) },
    };
    savedSettingsRef.current = settings;
    fetcher.submit({ intent: "saveSettings", settings: JSON.stringify(settings) }, { method: "post" });
  };

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "saveSettings") {
      return;
    }
    if (isActionError(fetcher.data)) {
      notify(<ToastNotification kind="error" title={fetcher.data.error.title} subtitle={fetcher.data.error.message} />);
      return;
    }
    notify(<ToastNotification kind="success" title="Save Settings" subtitle="Successfully saved workflow settings" />);
    if (savedSettingsRef.current) {
      revisionDispatch({
        type: RevisionActionTypes.Set,
        data: { ...revisionState, ...savedSettingsRef.current, hasUnsavedUpdates: false },
      });
      savedSettingsRef.current = null;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

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

      createRevisionCallbackRef.current = typeof callback === "function" ? callback : null;
      missingWorkspaceParamsRef.current = missingWorkspaceParams(
        workfowDagObject.nodes,
        groupTasksByName(taskList),
        availableParameters,
      );
      fetcher.submit({ intent: "createRevision", revision: JSON.stringify(revision) }, { method: "post" });
    }
  };

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "createRevision") {
      return;
    }

    if (isActionError(fetcher.data)) {
      notify(<ToastNotification kind="error" title={fetcher.data.error.title} subtitle={fetcher.data.error.message} />);
      return;
    }

    const data = fetcher.data.workflow;
    notify(<ToastNotification kind="success" title="Create Version" subtitle="Successfully created workflow version" />);
    const missing = missingWorkspaceParamsRef.current;
    if (missing.length > 0) {
      notify(
        <ToastNotification
          kind="warning"
          title={missing.length > 1 ? "Workspace parameters missing" : "Workspace parameter missing"}
          subtitle={missingWorkspaceParamsMessage(missing)}
        />,
      );
    }
    createRevisionCallbackRef.current?.();
    createRevisionCallbackRef.current = null;
    revisionDispatch({ type: RevisionActionTypes.Set, data });
    // The newly created revision is now the one being viewed, exactly as the old
    // `setRevisionNumber(data.version)` did. Because the version lives in the URL, this is a
    // navigation - react-router resolves the loader before committing it, so the `key` below and
    // the loader data it re-initialises this reducer from change in the same commit.
    changeVersion(data.version);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

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

  const revisionCount = changeLogData.length;
  const { markdown, version } = revisionState;
  const mode = version === revisionCount ? WorkflowEngineMode.Edit : WorkflowEngineMode.View;
  const store = useMemo(() => {
    return {
      availableParameters,
      mode,
      revisionDispatch,
      revisionState,
      workflowsQueryData: workflowsData,
    };
  }, [availableParameters, mode, revisionDispatch, revisionState, workflowsData]);

  // Same in-app "leave without saving" guard as before, ported from v5's <Prompt> to v6/v7's
  // useBlocker (requires the data router set up in Root.tsx). Blocks navigation away from
  // this workflow while there are unsaved changes; switching tabs within the same workflow
  // (nextLocation.pathname still includes workflowRef) is allowed through unprompted - as is the
  // search-param-only navigation the version switcher now makes, for the same reason.
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

  const isCreatingRevision = fetcher.state !== "idle";
  const isSavingSettings = fetcher.state !== "idle" && fetcher.formData?.get("intent") === "saveSettings";
  const createRevisionFailed = Boolean(
    fetcher.data && fetcher.data.intent === "createRevision" && isActionError(fetcher.data),
  );

  return (
    // Must create context to share state w/ nodes that are created by the DAG engine
    <EditorContextProvider value={store}>
      <>
        <Header
          changeLog={changeLogData}
          changeRevision={changeVersion}
          createRevision={handleCreateRevision}
          canCreateNewVersion={CREATEABLE_PATHS.includes(location.pathname.split("/").pop() || "")}
          createRevisionFailed={createRevisionFailed}
          isCreatingRevision={isCreatingRevision}
          isReadOnlyTasks={readOnlyTasks}
          isSavingSettings={isSavingSettings}
          saveSettings={handleSaveSettings}
          revisionState={revisionState}
          viewType={WorkflowView.Workflow}
          revisionCount={revisionCount}
        />
        <Routes>
          <Route path="canvas" element={null} />
          <Route
            path="parameters"
            element={
              <Parameters workflow={revisionState} handleUpdateParams={handleUpdateParams} readOnly={readOnlyTasks} />
            }
          />
          <Route path="schedule" element={<Schedule workflow={revisionState} />} />
          <Route path="changelog" element={<ChangeLog changeLogData={changeLogData} />} />
        </Routes>
        {
          // Always render parent Configure component so state isn't lost when switching tabs
          // It is responsible for rendering its children, but Formik form management is always mounted
          <>
            <Designer
              notes={markdown}
              readOnly={readOnlyTasks}
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
 * Update the `workspace` and `labels` to be in the right format
 */
export function formatConfigureValues(configureValues: ConfigureWorkflowFormValues): Partial<WorkflowCanvas> {
  // `templateVersion` is the form's pin for a template-backed workflow, sent only as `templateRef` (handleSaveSettings).
  const {
    workspace: workspaceForm,
    concurrencyLimit,
    concurrencyOverflow,
    templateVersion: _templateVersion,
    ...optionalConfigureValues
  } = configureValues;

  // Format labels
  const labelsKVObject = configureValues.labels.reduce(
    (accum, current) => {
      accum[current.key] = current.value;
      return accum;
    },
    {} as Record<string, string>,
  );

  /*
   * The form's `enabled` toggle is form-only: on, the run's shared workspace is sent as the API's
   * `workspace` object with the size written as a Gi quantity; off, an explicit null so the server
   * clears a previously declared one (an absent key would leave it untouched). An empty mount path
   * is omitted so the server applies its default.
   */
  const workspace = workspaceForm?.enabled
    ? { size: gbToQuantity(workspaceForm.size), ...(workspaceForm.mountPath ? { mountPath: workspaceForm.mountPath } : {}) }
    : null;

  const formattedWorkflowConfig: Partial<WorkflowCanvas> = {
    ...optionalConfigureValues,
    // `null` on the wire is the clear signal; the type only allows the object or absence.
    workspace: workspace as WorkflowCanvas["workspace"],
    labels: labelsKVObject,
    timeout: configureValues.timeout ?? undefined,
    retries: configureValues.retries ?? undefined,
  };

  // The limit and the overflow choice are two form fields; the API takes them as one object, and
  // only when a limit is set.
  if (concurrencyLimit !== null && concurrencyLimit !== undefined && String(concurrencyLimit) !== "") {
    formattedWorkflowConfig.concurrency = { limit: Number(concurrencyLimit), overflow: concurrencyOverflow ?? "queue" };
  }

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
