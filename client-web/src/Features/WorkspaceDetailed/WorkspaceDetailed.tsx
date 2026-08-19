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
import { useAppContext, useWorkspaceContext } from "Hooks";
import { AppPath, FeatureFlag } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import ApproverGroups from "./ApproverGroups";
import Header from "./Header";
import Members from "./Members";
import Quotas from "./Quotas";
import Settings from "./Settings";
import Tokens from "./Tokens";
import Workflows from "./Workflows";
import styles from "./workspaceDetailed.module.scss";

const FeatureLayout: React.FC<React.PropsWithChildren> = ({ children }) => {
  return (
    <>
      <Helmet>
        <title>Workspaces</title>
      </Helmet>
      <FeatureHeader
        includeBorder={false}
        header={
          <>
            <HeaderTitle style={{ margin: "0" }}>Workspaces</HeaderTitle>
            <HeaderSubtitle>View and manage your workspaces</HeaderSubtitle>
          </>
        }
      />
      <Box p="1rem">{children}</Box>
    </>
  );
};

function WorkspaceDetailedContainer() {
  const workspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);
  const { workspace } = useWorkspaceContext();
  const { user } = useAppContext();

  const workspaceDetailsUrl = serviceUrl.resourceWorkspace({ workspace: workspace.name });

  const workspaceDetailsQuery = useQuery({
    queryKey: workspaceDetailsUrl,
    queryFn: resolver.query(workspaceDetailsUrl),
  });

  if (workspaceDetailsQuery.isLoading)
    return (
      <FeatureLayout>
        <Loading />
      </FeatureLayout>
    );

  if (workspaceDetailsQuery.error)
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );

  if (workspaceDetailsQuery.data) {
    const canEdit = workspaceManagementEnabled && workspaceDetailsQuery.data.status === "active";
    // const workspaceOwnerIdList = workspaceDetailsData?.owners?.map((owner) => owner.ownerId);
    return (
      <div className={styles.container}>
        <Header workspace={workspaceDetailsQuery.data} />
        <Switch>
          <Route exact path={AppPath.ManageWorkspace}>
            <Members canEdit={canEdit} workspace={workspaceDetailsQuery.data} user={user} workspaceDetailsUrl={workspaceDetailsUrl} />
          </Route>
          <Route exact path={AppPath.ManageWorkspaceWorkflows}>
            <Workflows workspace={workspaceDetailsQuery.data} />
          </Route>
          <Route exact path={AppPath.ManageWorkspaceApprovers}>
            <ApproverGroups workspace={workspaceDetailsQuery.data} canEdit={canEdit} workspaceDetailsUrl={workspaceDetailsUrl} />
          </Route>
          <Route exact path={AppPath.ManageWorkspaceQuotas}>
            <Quotas
              workspace={workspaceDetailsQuery.data}
              canEdit={canEdit && user?.type === "admin"}
              workspaceDetailsUrl={workspaceDetailsUrl}
            />
          </Route>
          <Route exact path={AppPath.ManageWorkspaceTokens}>
            <Tokens workspace={workspaceDetailsQuery.data} canEdit={canEdit} />
          </Route>
          <Route exact path={AppPath.ManageWorkspaceSettings}>
            <Settings workspace={workspaceDetailsQuery.data} canEdit={canEdit} />
          </Route>
        </Switch>
      </div>
    );
  }

  return null;
}

export default WorkspaceDetailedContainer;
