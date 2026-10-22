import React, { lazy, Suspense } from "react";
import { useFeature } from "flagged";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Navigate, Route, Routes, useLoaderData } from "react-router-dom";
import { useNavigate } from "react-router-dom";
import { Box } from "reflexbox";
import ClientOnly from "Components/ClientOnly";
import ErrorDragon from "Components/ErrorDragon";
import WombatMessage from "Components/WombatMessage";
import { useWorkspaceContext } from "Hooks";
import { appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { HttpMethod } from "Constants";
import { formatErrorMessage } from "@boomerang-io/utils";
import { ChangeLog, Task } from "Types";
import { actionError, type ActionError } from "Utils/actionResult";
import Sidenav from "../Sidenav";
import styles from "../TaskManager.module.scss";
import TaskTemplateOverview from "../TaskTemplateOverview";

// See AdminTasks.tsx for why this is lazy + ClientOnly rather than a plain import: CodeMirror 5
// (inside TaskTemplateEditor) reads `navigator`/`document` at module scope and cannot be
// evaluated in Node. The loader below fetches the yaml text itself via serverFetch rather than
// importing TaskTemplateEditor, so CodeMirror never enters the server bundle.
const TaskTemplateYamlEditor = lazy(() => import("../TaskTemplateEditor"));

const HELMET_TITLE = "Workspace Task Manager";

// Mirrors AdminTasks.tsx's loader/action design (see its comments for the full rationale on
// keeping the internal <Routes> un-lifted and driving the ":name/:version[/editor]" sub-pages off
// the "/*" splat instead). The one difference: every fetch here is workspace-scoped, using the
// `:workspace` route param (available to the loader/action directly - no need to reach for
// useWorkspaceContext, which only exists client-side).
type LoaderData = {
  tasks: Array<Task>;
  errorLoadingTasks: boolean;
  selectedTask: Task | null;
  changelog: ChangeLog | null;
  errorLoadingSelected: boolean;
  yaml: string | null;
  errorLoadingYaml: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string; "*"?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const api = serverFetch(request);
  const [name, version, subroute] = (params["*"] ?? "").split("/").filter(Boolean);

  // Same sequencing as AdminTasks.tsx (see its comment): the sidenav's task list is independent of
  // the selected template, so it goes out in the same wave as the task/changelog pair. Each group
  // keeps the failure semantics it had.
  const tasksPromise = api
    .get(serviceUrl.workspace.task.queryTasks({ workspace, query: queryString.stringify({ statuses: "active,inactive" }) }))
    .then((response) => ({ tasks: response.data.content as Array<Task>, errorLoadingTasks: false }))
    .catch(() => ({ tasks: [] as Array<Task>, errorLoadingTasks: true }));

  const selectedPromise =
    name && version
      ? Promise.all([
          api.get(serviceUrl.workspace.task.getTask({ workspace, name, version })),
          api.get(serviceUrl.workspace.task.getTaskChangelog({ workspace, name })),
        ])
          .then(([taskResponse, changelogResponse]) => ({
            selectedTask: taskResponse.data as Task | null,
            changelog: changelogResponse.data as ChangeLog | null,
            errorLoadingSelected: false,
          }))
          .catch(() => ({ selectedTask: null, changelog: null, errorLoadingSelected: true }))
      : Promise.resolve({ selectedTask: null, changelog: null, errorLoadingSelected: false });

  const [{ tasks, errorLoadingTasks }, { selectedTask, changelog, errorLoadingSelected }] = await Promise.all([
    tasksPromise,
    selectedPromise,
  ]);

  // Genuinely dependent, so it stays sequential: only for the editor sub-route, and only once the
  // selected template is known to have resolved.
  let yaml: string | null = null;
  let errorLoadingYaml = false;
  if (name && version && subroute === "editor" && !errorLoadingSelected) {
    try {
      const yamlResponse = await api.get(serviceUrl.workspace.task.getTask({ workspace, name, version }), {
        headers: { accept: "application/x-yaml" },
      });
      yaml = yamlResponse.data;
    } catch (error) {
      errorLoadingYaml = true;
    }
  }

  return { tasks, errorLoadingTasks, selectedTask, changelog, errorLoadingSelected, yaml, errorLoadingYaml };
}

type ActionResult =
  | { intent: "apply" | "applyYaml"; task: Task }
  | ({ intent: "apply" | "applyYaml" } & ActionError)
  | { intent: "validateYaml" }
  | ({ intent: "validateYaml" } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "validateYaml") {
    const body = String(formData.get("body"));
    try {
      await serverFetch(request)({
        url: serviceUrl.workspace.task.postValidateYaml({ workspace }),
        data: body,
        method: HttpMethod.Post,
        headers: { "content-type": "application/x-yaml" },
      });
      return { intent: "validateYaml" as const };
    } catch (error) {
      return actionError({
        intent: "validateYaml" as const,
        error: formatErrorMessage({ error, defaultMessage: "The uploaded file could not be validated." }),
      });
    }
  }

  const name = String(formData.get("name"));
  const replace = formData.get("replace") === "true";
  const body = String(formData.get("body"));

  if (intent === "applyYaml") {
    try {
      const response = await serverFetch(request)({
        url: serviceUrl.workspace.task.putTask({ workspace, name, replace }),
        data: body,
        method: HttpMethod.Put,
        headers: { "content-type": "application/x-yaml" },
      });
      return { intent: "applyYaml" as const, task: response.data };
    } catch (error) {
      return actionError({
        intent: "applyYaml" as const,
        error: formatErrorMessage({ error, defaultMessage: "Request to save task template failed." }),
      });
    }
  }

  try {
    const response = await serverFetch(request)({
      url: serviceUrl.workspace.task.putTask({ workspace, name, replace }),
      data: JSON.parse(body),
      method: HttpMethod.Put,
    });
    return { intent: "apply" as const, task: response.data };
  } catch (error) {
    return actionError({
      intent: "apply" as const,
      error: formatErrorMessage({ error, defaultMessage: "Request to save task template failed." }),
    });
  }
}

function TaskTemplatesContainer() {
  const { tasks, errorLoadingTasks, selectedTask, changelog, errorLoadingSelected, yaml, errorLoadingYaml } =
    useLoaderData() as LoaderData;
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const editVerifiedTasksEnabled = useFeature(FeatureFlag.EditVerifiedTasksEnabled);

  /** Check if there is an active workspace or redirect to home */
  if (!workspace) {
    navigate(appLink.home());
    return null;
  }

  if (errorLoadingTasks) {
    return (
      <div className={styles.container}>
        <Helmet>
          <title>{HELMET_TITLE}</title>
        </Helmet>
        <ErrorDragon />
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <Helmet>
        <title>{HELMET_TITLE}</title>
      </Helmet>
      <Sidenav
        workspace={workspace}
        tasks={tasks}
        getTaskTemplatesUrl={serviceUrl.workspace.task.queryTasks({ workspace: workspace.name, query: "statuses=active,inactive" })}
      />
      <Routes>
        <Route
          index
          element={
            <Box maxWidth="24rem" margin="0 auto">
              <WombatMessage className={styles.wombat} title="Select a task or create one" />
            </Box>
          }
        />
        <Route
          path=":name/:version/editor"
          element={
            <ClientOnly>
              {() => (
                <Suspense fallback={null}>
                  <TaskTemplateYamlEditor
                    editVerifiedTasksEnabled={editVerifiedTasksEnabled}
                    selectedTaskTemplate={selectedTask}
                    changelog={changelog}
                    yaml={yaml}
                    errorLoading={errorLoadingSelected || errorLoadingYaml}
                  />
                </Suspense>
              )}
            </ClientOnly>
          }
        />
        <Route
          path=":name/:version"
          element={
            <TaskTemplateOverview
              taskTemplates={tasks}
              editVerifiedTasksEnabled={editVerifiedTasksEnabled}
              selectedTaskTemplate={selectedTask}
              changelog={changelog}
              errorLoading={errorLoadingSelected}
            />
          }
        />
        <Route path="*" element={<Navigate to={appLink.manageTasks({ workspace: workspace.name })} replace />} />
      </Routes>
    </div>
  );
}

export default TaskTemplatesContainer;
