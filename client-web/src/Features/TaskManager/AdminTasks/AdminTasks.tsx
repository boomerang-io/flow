import React, { lazy, Suspense } from "react";
import { useFeature } from "flagged";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Navigate, Route, Routes, useLoaderData } from "react-router-dom";
import { Box } from "reflexbox";
import ClientOnly from "Components/ClientOnly";
import ErrorDragon from "Components/ErrorDragon";
import WombatMessage from "Components/WombatMessage";
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

// TaskTemplateEditor pulls in CodeMirror 5, which reads `navigator`/`document` at module scope -
// genuinely unrenderable in Node (see CLAUDE.md client-web SSR rules). Unlike TextEditorModal
// (gated behind a modal's isOpen flag, which is always false on first render), this component
// renders directly off a URL match (":name/:version/editor"), so React.lazy alone isn't enough -
// SSR would still invoke the dynamic import for that exact route. ClientOnly gates rendering
// (and therefore the import) until after the client has mounted. The loader below never imports
// this module - it fetches the yaml text itself via serverFetch - so CodeMirror stays out of the
// server (and route-module) bundle entirely.
const TaskTemplateYamlEditor = lazy(() => import("../TaskTemplateEditor"));

const HELMET_TITLE = "Task Manager";

// Route module: this file's `loader`/`action` are attached to the route in app/routes.ts
// (path="/admin/task-manager/*") rather than being defined inline there, so the data-fetching
// code stays next to the component that consumes it.
//
// The internal <Routes> below (":name/:version", ":name/:version/editor") are NOT lifted into
// app/routes.ts as real nested routes - a real route file per task-template sub-page isn't
// possible here since the loader can't know `name`/`version` from the router config alone, and
// lifting would restructure the shared route config, which the batch instructions call out as a
// stop-and-report decision. Instead this single loader inspects the splat (`params["*"]`) that
// react-router hands a "/*" route to figure out which sub-page (if any) is being requested, and
// fetches everything that page needs in one pass. Because react-router re-runs a route's loader
// whenever the resolved Location for that route changes - including the splat value - internal
// navigation between task templates still re-fetches correctly even though the route pattern
// itself ("/admin/task-manager/*") never changes.
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
  params: { "*"?: string };
  request: Request;
}): Promise<LoaderData> {
  const api = serverFetch(request);
  const [name, version, subroute] = (params["*"] ?? "").split("/").filter(Boolean);

  // The sidenav's task list has no dependency on the selected template, so it goes out in the same
  // wave as the task/changelog pair rather than ahead of it - a loader blocks first paint, and
  // there is no pending UI behind it (useNavigation() is used nowhere in the app). Each of the two
  // groups keeps the failure semantics it had: the list degrades to `errorLoadingTasks`, and the
  // pair still fails as a unit (either response missing means no template to render).
  const tasksPromise = api
    .get(serviceUrl.task.queryTasks({ query: queryString.stringify({ statuses: "active,inactive" }) }))
    .then((response) => ({ tasks: response.data.content as Array<Task>, errorLoadingTasks: false }))
    .catch(() => ({ tasks: [] as Array<Task>, errorLoadingTasks: true }));

  const selectedPromise =
    name && version
      ? Promise.all([
          api.get(serviceUrl.task.getTask({ name, version })),
          api.get(serviceUrl.task.getTaskChangelog({ name })),
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

  // Genuinely dependent, so it stays sequential: the YAML is only fetched for the editor
  // sub-route, and only once the selected template is known to have resolved.
  let yaml: string | null = null;
  let errorLoadingYaml = false;
  if (name && version && subroute === "editor" && !errorLoadingSelected) {
    try {
      const yamlResponse = await api.get(serviceUrl.task.getTask({ name, version }), {
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

// One action, keyed by `intent`, for every write this route's sub-components perform (create,
// import, save, archive, restore, and the file-import validation step) - see
// CLAUDE.md/GlobalParameters.tsx for why: a single fetcher target per route module.
export async function action({ request }: { request: Request }) {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "validateYaml") {
    const body = String(formData.get("body"));
    try {
      await serverFetch(request)({
        url: serviceUrl.task.postValidateYaml(),
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
        url: serviceUrl.task.putTask({ name, replace }),
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
      url: serviceUrl.task.putTask({ name, replace }),
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
  const editVerifiedTasksEnabled = useFeature(FeatureFlag.EditVerifiedTasksEnabled);

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
      <Sidenav tasks={tasks} getTaskTemplatesUrl={serviceUrl.task.queryTasks({ query: "statuses=active,inactive" })} />
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
        <Route path="*" element={<Navigate to={appLink.adminTasks()} replace />} />
      </Routes>
    </div>
  );
}

export default TaskTemplatesContainer;
