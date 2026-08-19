import React from "react";
import { ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { Route, Routes, useLoaderData } from "react-router-dom";
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

// Route module: loader lives next to the component it feeds, and is attached to the route in
// AppRoutes.tsx (path={AppPath.User}, a `:userId` URL param). By the time this component
// renders, the router has already resolved the loader (navigation blocks on it), so there's no
// isLoading branch here any more - only the errorLoading branch remains, for a request that
// resolves but fails.
type LoaderData = {
  userDetails: FlowUser | null;
  errorLoading: boolean;
};

export async function loader({ params }: { params: { userId?: string } }): Promise<LoaderData> {
  const userDetailsUrl = serviceUrl.getUser({ userId: params.userId });
  try {
    const userDetails = await resolver.query(userDetailsUrl)();
    return { userDetails, errorLoading: false };
  } catch (error) {
    return { userDetails: null, errorLoading: true };
  }
}

interface FeatureLayoutProps {
  isError?: boolean;
  user?: FlowUser;
  children: any;
}

const FeatureLayout = ({ children, isError }: FeatureLayoutProps) => {
  return (
    <>
      <Helmet>
        <title>User</title>
      </Helmet>
      <Header isError={isError} />
      <Box p="1rem">{children}</Box>
    </>
  );
};

function WorkspaceDetailedContainer() {
  const userManagementEnabled = useFeature(FeatureFlag.UserManagementEnabled);
  const { workspaces } = useAppContext();
  const { userDetails, errorLoading } = useLoaderData() as LoaderData;

  if (errorLoading || !userDetails) {
    return (
      <FeatureLayout isError>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  return (
    <div className={styles.container}>
      <Header user={userDetails} userManagementEnabled={userManagementEnabled} />
      <Routes>
        <Route path="" element={<Workspaces user={userDetails} workspaces={workspaces} />} />
        <Route path="labels" element={<Labels user={userDetails} userManagementEnabled={userManagementEnabled} />} />
        <Route path="settings" element={<Settings user={userDetails} userManagementEnabled={userManagementEnabled} />} />
      </Routes>
    </div>
  );
}

export default WorkspaceDetailedContainer;
