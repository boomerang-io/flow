import React from "react";
import { useFeature } from "flagged";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Route, Switch, useRouteMatch, Redirect } from "react-router-dom";
import { Box } from "reflexbox";
import ErrorDragon from "Components/ErrorDragon";
import WombatMessage from "Components/WombatMessage";
import { useQuery } from "Hooks";
import { AppPath, appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import Sidenav from "../Sidenav";
import styles from "../TaskManager.module.scss";
import TaskTemplateYamlEditor from "../TaskTemplateEditor";
import TaskTemplateOverview from "../TaskTemplateOverview";

const HELMET_TITLE = "Task Manager";

function TaskTemplatesContainer() {
  const match = useRouteMatch();
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
      <Switch>
        <Route exact path={match.path}>
          <Box maxWidth="24rem" margin="0 auto">
            <WombatMessage className={styles.wombat} title="Select a task or create one" />
          </Box>
        </Route>
        <Route path={AppPath.TasksEditor} strict={true}>
          <TaskTemplateYamlEditor
            taskTemplates={tasksData?.content}
            editVerifiedTasksEnabled={editVerifiedTasksEnabled}
            getTaskTemplatesUrl={getTaskTemplatesUrl}
          />
        </Route>
        <Route path={AppPath.TasksDetail} strict={true}>
          <TaskTemplateOverview
            taskTemplates={tasksData?.content}
            editVerifiedTasksEnabled={editVerifiedTasksEnabled}
            getTaskTemplatesUrl={getTaskTemplatesUrl}
          />
        </Route>
        <Redirect to={appLink.adminTasks()} />
      </Switch>
    </div>
  );
}

export default TaskTemplatesContainer;
