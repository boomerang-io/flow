import React from "react";
import {
  ErrorMessage,
  FeatureHeader,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { useQuery } from "react-query";
import { Switch, Route } from "react-router-dom";
import { Box } from "reflexbox";
import { useAppContext, useTeamContext } from "Hooks";
import { AppPath, FeatureFlag } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import ApproverGroups from "./ApproverGroups";
import Header from "./Header";
import Members from "./Members";
import Quotas from "./Quotas";
import Settings from "./Settings";
import Tokens from "./Tokens";
import Workflows from "./Workflows";
import styles from "./teamDetailed.module.scss";

const FeatureLayout: React.FC = ({ children }) => {
  return (
    <>
      <Helmet>
        <title>Teams</title>
      </Helmet>
      <FeatureHeader
        includeBorder={false}
        header={
          <>
            <HeaderTitle style={{ margin: "0" }}>Teams</HeaderTitle>
            <HeaderSubtitle>View and manage your teams</HeaderSubtitle>
          </>
        }
      />
      <Box p="1rem">{children}</Box>
    </>
  );
};

function TeamDetailedContainer() {
  const teamManagementEnabled = useFeature(FeatureFlag.TeamManagementEnabled);
  const { team } = useTeamContext();
  const { user } = useAppContext();

  const teamDetailsUrl = serviceUrl.resourceTeam({ team: team.name });

  const teamDetailsQuery = useQuery({
    queryKey: teamDetailsUrl,
    queryFn: resolver.query(teamDetailsUrl),
  });

  if (teamDetailsQuery.isLoading)
    return (
      <FeatureLayout>
        <Loading />
      </FeatureLayout>
    );

  if (teamDetailsQuery.error)
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );

  if (teamDetailsQuery.data) {
    const canEdit = teamManagementEnabled && teamDetailsQuery.data.status === "active";
    // const teamOwnerIdList = teamDetailsData?.owners?.map((owner) => owner.ownerId);
    return (
      <div className={styles.container}>
        <Header team={teamDetailsQuery.data} />
        <Switch>
          <Route exact path={AppPath.ManageTeam}>
            <Members canEdit={canEdit} team={teamDetailsQuery.data} user={user} teamDetailsUrl={teamDetailsUrl} />
          </Route>
          <Route exact path={AppPath.ManageTeamWorkflows}>
            <Workflows team={teamDetailsQuery.data} />
          </Route>
          <Route exact path={AppPath.ManageTeamApprovers}>
            <ApproverGroups team={teamDetailsQuery.data} canEdit={canEdit} teamDetailsUrl={teamDetailsUrl} />
          </Route>
          <Route exact path={AppPath.ManageTeamQuotas}>
            <Quotas
              team={teamDetailsQuery.data}
              canEdit={canEdit && user?.type === "admin"}
              teamDetailsUrl={teamDetailsUrl}
            />
          </Route>
          <Route exact path={AppPath.ManageTeamTokens}>
            <Tokens team={teamDetailsQuery.data} canEdit={canEdit} />
          </Route>
          <Route exact path={AppPath.ManageTeamSettings}>
            <Settings team={teamDetailsQuery.data} canEdit={canEdit} />
          </Route>
        </Switch>
      </div>
    );
  }

  return null;
}

export default TeamDetailedContainer;
