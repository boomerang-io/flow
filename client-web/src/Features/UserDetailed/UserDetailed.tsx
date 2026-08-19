import React from "react";
import { ErrorMessage, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { useQuery } from "react-query";
import { Route, Routes, useParams } from "react-router-dom";
import { Box } from "reflexbox";
import { useAppContext } from "Hooks";
import { FeatureFlag } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowUser } from "Types";
import Header from "./Header";
import Labels from "./Labels";
import Settings from "./Settings";
import Workspaces from "./Workspaces";
import styles from "./UserDetailed.module.scss";

interface FeatureLayoutProps {
  isError?: boolean;
  isLoading?: boolean;
  user?: FlowUser;
  children: any;
}

const FeatureLayout = ({ children, isLoading, isError }: FeatureLayoutProps) => {
  return (
    <>
      <Helmet>
        <title>User</title>
      </Helmet>
      <Header isError={isError} isLoading={isLoading} />
      <Box p="1rem">{children}</Box>
    </>
  );
};

function WorkspaceDetailedContainer() {
  const userManagementEnabled = useFeature(FeatureFlag.UserManagementEnabled);
  const { workspaces } = useAppContext();
  const { userId } = useParams<{ userId: string }>();

  const userDetailsUrl = serviceUrl.getUser({ userId });

  const {
    data: userDetailsData,
    isError: userDetailsIsError,
    isLoading: userDetailsIsLoading,
  } = useQuery({
    queryKey: userDetailsUrl,
    queryFn: resolver.query(userDetailsUrl),
  });

  if (userDetailsIsLoading)
    return (
      <FeatureLayout isLoading={userDetailsIsLoading}>
        <Loading />
      </FeatureLayout>
    );
  if (userDetailsIsError)
    return (
      <FeatureLayout isError={userDetailsIsError}>
        <ErrorMessage />
      </FeatureLayout>
    );

  if (userDetailsData) {
    return (
      <div className={styles.container}>
        <Header user={userDetailsData} userManagementEnabled={userManagementEnabled} />
        <Routes>
          <Route path="" element={<Workspaces user={userDetailsData} workspaces={workspaces} />} />
          <Route path="labels" element={<Labels user={userDetailsData} userManagementEnabled={userManagementEnabled} />} />
          <Route path="settings" element={<Settings user={userDetailsData} userManagementEnabled={userManagementEnabled} />} />
        </Routes>
      </div>
    );
  }

  return null;
}

export default WorkspaceDetailedContainer;
