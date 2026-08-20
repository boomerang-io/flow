import React, { lazy, Suspense } from "react";
import { useFeature } from "flagged";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Navigate, Route, Routes } from "react-router-dom";
import { Box } from "reflexbox";
import ClientOnly from "Components/ClientOnly";
import ErrorDragon from "Components/ErrorDragon";
import WombatMessage from "Components/WombatMessage";
import { useQuery } from "Hooks";
import { appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import Sidenav from "../Sidenav";
import styles from "../TaskManager.module.scss";
import TaskTemplateOverview from "../TaskTemplateOverview";

// TaskTemplateEditor pulls in CodeMirror 5, which reads `navigator`/`document` at module scope -
// genuinely unrenderable in Node (see CLAUDE.md client-web SSR rules). Unlike TextEditorModal
// (gated behind a modal's isOpen flag, which is always false on first render), this component
// renders directly off a URL match (":name/:version/editor"), so React.lazy alone isn't enough -
// SSR would still invoke the dynamic import for that exact route. ClientOnly gates rendering
// (and therefore the import) until after the client has mounted.
const TaskTemplateYamlEditor = lazy(() => import("../TaskTemplateEditor"));

const HELMET_TITLE = "Task Manager";

function TaskTemplatesContainer() {
  const editVerifiedTasksEnabled = useFeature(FeatureFlag.EditVerifiedTasksEnabled);
  const getTaskTemplatesUrl = serviceUrl.task.queryTasks({
    query: queryString.stringify({ statuses: "active,inactive" }),
  });
  const { data: tasksData, error: tasksDataError, isLoading } = useQuery(getTaskTemplatesUrl);

  if (isLoading) {
    return (
      <div className={styles.container}>
        <Helmet>
          <title>{HELMET_TITLE}</title>
        </Helmet>
        <Sidenav isLoading tasks={[]} getTaskTemplatesUrl={getTaskTemplatesUrl} />
        <Box maxWidth="24rem" margin="0 auto">
          <WombatMessage className={styles.wombat} title="Retrieving Tasks..." />
        </Box>
      </div>
    );
  }

  if (tasksDataError) {
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
      <Sidenav tasks={tasksData?.content} getTaskTemplatesUrl={getTaskTemplatesUrl} />
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
                    taskTemplates={tasksData?.content}
                    editVerifiedTasksEnabled={editVerifiedTasksEnabled}
                    getTaskTemplatesUrl={getTaskTemplatesUrl}
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
              taskTemplates={tasksData?.content}
              editVerifiedTasksEnabled={editVerifiedTasksEnabled}
              getTaskTemplatesUrl={getTaskTemplatesUrl}
            />
          }
        />
        <Route path="*" element={<Navigate to={appLink.adminTasks()} replace />} />
      </Routes>
    </div>
  );
}

export default TaskTemplatesContainer;
