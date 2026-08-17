import React from "react";
import { useFeature } from "flagged";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Route, Switch, useRouteMatch, Redirect } from "react-router-dom";
import { useHistory } from "react-router-dom";
import { Box } from "reflexbox";
import ErrorDragon from "Components/ErrorDragon";
import WombatMessage from "Components/WombatMessage";
import { useQuery } from "Hooks";
import { useWorkspaceContext } from "Hooks";
import { AppPath, appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import Sidenav from "../Sidenav";
import styles from "../TaskManager.module.scss";
import TaskTemplateYamlEditor from "../TaskTemplateEditor";
import TaskTemplateOverview from "../TaskTemplateOverview";

const HELMET_TITLE = "Workspace Task Manager";

function TaskTemplatesContainer() {
  const { workspace } = useWorkspaceContext();
  const history = useHistory();
  const match = useRouteMatch();
  const editVerifiedTasksEnabled = useFeature(FeatureFlag.EditVerifiedTasksEnabled);
  const getWorkspaceTaskTemplatesUrl = serviceUrl.workspace.task.queryTasks({
    query: queryString.stringify({ statuses: "active,inactive" }),
    workspace: workspace.name,
  });
  const {
    data: tasksData,
    error: tasksDataError,
    isLoading,
  } = useQuery(getWorkspaceTaskTemplatesUrl, {
    enabled: Boolean(workspace),
  });

  /** Check if there is an active workspace or redirect to home */
  if (!workspace) {
    return history.push(appLink.home());
  }

  if (isLoading) {
    return (
      <div className={styles.container}>
        <Helmet>
          <title>{HELMET_TITLE}</title>
        </Helmet>
        <Sidenav isLoading tasks={[]} workspace={workspace} getTaskTemplatesUrl={getWorkspaceTaskTemplatesUrl} />
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
      <Sidenav workspace={workspace} tasks={tasksData?.content} getTaskTemplatesUrl={getWorkspaceTaskTemplatesUrl} />
      <Switch>
        <Route exact path={match.path}>
          <Box maxWidth="24rem" margin="0 auto">
            <WombatMessage className={styles.wombat} title="Select a task or create one" />
          </Box>
        </Route>
        <Route path={AppPath.ManageTasksEditor} strict={true}>
          <TaskTemplateYamlEditor
            taskTemplates={tasksData?.content}
            editVerifiedTasksEnabled={editVerifiedTasksEnabled}
            getTaskTemplatesUrl={getWorkspaceTaskTemplatesUrl}
          />
        </Route>
        <Route path={AppPath.ManageTasksDetail} strict={true}>
          <TaskTemplateOverview
            taskTemplates={tasksData?.content}
            editVerifiedTasksEnabled={editVerifiedTasksEnabled}
            getTaskTemplatesUrl={getWorkspaceTaskTemplatesUrl}
          />
        </Route>
        <Redirect to={appLink.manageTasks({ workspace: workspace.name })} />
      </Switch>
    </div>
  );
}

export default TaskTemplatesContainer;
