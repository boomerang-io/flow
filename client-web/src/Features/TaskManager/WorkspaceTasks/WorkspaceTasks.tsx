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
  let tasks: Array<Task> = [];
  let errorLoadingTasks = false;
  try {
    const response = await serverFetch(request).get(
      serviceUrl.workspace.task.queryTasks({ workspace, query: queryString.stringify({ statuses: "active,inactive" }) }),
    );
    tasks = response.data.content;
  } catch (error) {
    errorLoadingTasks = true;
  }

  const [name, version, subroute] = (params["*"] ?? "").split("/").filter(Boolean);

  let selectedTask: Task | null = null;
  let changelog: ChangeLog | null = null;
  let errorLoadingSelected = false;
  let yaml: string | null = null;
  let errorLoadingYaml = false;

  if (name && version) {
    try {
      const [taskResponse, changelogResponse] = await Promise.all([
        serverFetch(request).get(serviceUrl.workspace.task.getTask({ workspace, name, version })),
        serverFetch(request).get(serviceUrl.workspace.task.getTaskChangelog({ workspace, name })),
      ]);
      selectedTask = taskResponse.data;
      changelog = changelogResponse.data;
    } catch (error) {
      errorLoadingSelected = true;
    }

    if (subroute === "editor" && !errorLoadingSelected) {
      try {
        const yamlResponse = await serverFetch(request).get(serviceUrl.workspace.task.getTask({ workspace, name, version }), {
          headers: { accept: "application/x-yaml" },
        });
        yaml = yamlResponse.data;
      } catch (error) {
        errorLoadingYaml = true;
      }
    }
  }

  return { tasks, errorLoadingTasks, selectedTask, changelog, errorLoadingSelected, yaml, errorLoadingYaml };
}

type ActionResult =
  | { ok: true; intent: "apply" | "applyYaml"; task: Task }
  | { ok: false; intent: "apply" | "applyYaml"; error: { title: string; message: string } }
  | { ok: true; intent: "validateYaml" }
  | { ok: false; intent: "validateYaml"; error: { title: string; message: string } };

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ActionResult> {
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
      return { ok: true, intent: "validateYaml" };
    } catch (error) {
      return {
        ok: false,
        intent: "validateYaml",
        error: formatErrorMessage({ error, defaultMessage: "The uploaded file could not be validated." }),
      };
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
      return { ok: true, intent: "applyYaml", task: response.data };
    } catch (error) {
      return {
        ok: false,
        intent: "applyYaml",
        error: formatErrorMessage({ error, defaultMessage: "Request to save task template failed." }),
      };
    }
  }

  try {
    const response = await serverFetch(request)({
      url: serviceUrl.workspace.task.putTask({ workspace, name, replace }),
      data: JSON.parse(body),
      method: HttpMethod.Put,
    });
    return { ok: true, intent: "apply", task: response.data };
  } catch (error) {
    return {
      ok: false,
      intent: "apply",
      error: formatErrorMessage({ error, defaultMessage: "Request to save task template failed." }),
    };
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
