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
import { useTeamContext } from "Hooks";
import { AppPath, appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import Sidenav from "../Sidenav";
import styles from "../TaskManager.module.scss";
import TaskTemplateYamlEditor from "../TaskTemplateEditor";
import TaskTemplateOverview from "../TaskTemplateOverview";

const HELMET_TITLE = "Team Task Manager";

function TaskTemplatesContainer() {
  const { team } = useTeamContext();
  const history = useHistory();
  const match = useRouteMatch();
  const editVerifiedTasksEnabled = useFeature(FeatureFlag.EditVerifiedTasksEnabled);
  const getTeamTaskTemplatesUrl = serviceUrl.team.task.queryTasks({
    query: queryString.stringify({ statuses: "active,inactive" }),
    team: team.name,
  });
  const {
    data: tasksData,
    error: tasksDataError,
    isLoading,
  } = useQuery(getTeamTaskTemplatesUrl, {
    enabled: Boolean(team),
  });

  /** Check if there is an active team or redirect to home */
  if (!team) {
    return history.push(appLink.home());
  }

  if (isLoading) {
    return (
      <div className={styles.container}>
        <Helmet>
          <title>{HELMET_TITLE}</title>
        </Helmet>
        <Sidenav isLoading tasks={[]} team={team} getTaskTemplatesUrl={getTeamTaskTemplatesUrl} />
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
      <Sidenav team={team} tasks={tasksData?.content} getTaskTemplatesUrl={getTeamTaskTemplatesUrl} />
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
            getTaskTemplatesUrl={getTeamTaskTemplatesUrl}
          />
        </Route>
        <Route path={AppPath.ManageTasksDetail} strict={true}>
          <TaskTemplateOverview
            taskTemplates={tasksData?.content}
            editVerifiedTasksEnabled={editVerifiedTasksEnabled}
            getTaskTemplatesUrl={getTeamTaskTemplatesUrl}
          />
        </Route>
        <Redirect to={appLink.manageTasks({ team: team.name })} />
      </Switch>
    </div>
  );
}

export default TaskTemplatesContainer;
