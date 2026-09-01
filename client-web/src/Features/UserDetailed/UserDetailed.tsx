import React from "react";
import { ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { Route, Routes, useLoaderData } from "react-router-dom";
import { Box } from "reflexbox";
import { useAppContext } from "Hooks";
import { FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowUser } from "Types";
import { actionError, type ActionError } from "Utils/actionResult";
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

// Server loader (ssr:true - see CLAUDE.md client-web SSR direction; server loaders are the
// default now). Runs in Node, so it uses serverFetch(request) rather than the browser
// `resolver`/`axios` instance in Config/servicesConfig.ts - see Config/serverFetch.ts for the
// session-cookie-forwarding contract, unverified end-to-end until the auth exchange endpoint in
// specifications/authentication.md lands.
export async function loader({
  params,
  request,
}: {
  params: { userId?: string };
  request: Request;
}): Promise<LoaderData> {
  const userDetailsUrl = serviceUrl.getUser({ userId: params.userId });
  try {
    const response = await serverFetch(request).get(userDetailsUrl);
    return { userDetails: response.data, errorLoading: false };
  } catch (error) {
    return { userDetails: null, errorLoading: true };
  }
}

/*
 * One action serves both write sites under this route (Header/ChangeRole and Labels/UserLabels),
 * keyed by an `intent` form field; each submits through a bare useFetcher(), which resolves to
 * the nearest matched route's action - this one. (The inner <Routes> below is a plain component
 * switch, not router route matching, so "nearest matched route" is always this one route.)
 *
 * SECURITY: the user being modified is taken from the `:userId` ROUTE param, never from a form
 * field the browser supplies - a submission cannot retarget the write at a different user than
 * the URL the caller navigated to and was authorised for. The API call itself goes through
 * serverFetch(request), which forwards the caller's inbound session Cookie; a bare browser
 * axios instance here instead would send no credentials at all, because a `withCredentials`
 * default (how the deleted `resolver` object authenticated) needs a browser cookie jar that
 * Node does not have.
 */
export type UserDetailedActionResult =
  | { intent: "changeRole" | "saveLabels" }
  | ({ intent: "changeRole" | "saveLabels" } & ActionError);

export async function action({ params, request }: { params: { userId?: string }; request: Request }) {
  const userId = String(params.userId);
  const formData = await request.formData();
  const intent = String(formData.get("intent")) as "changeRole" | "saveLabels";

  const body =
    intent === "changeRole"
      ? { type: String(formData.get("type")) }
      : { labels: JSON.parse(String(formData.get("labels"))) };

  try {
    await serverFetch(request).patch(serviceUrl.getUser({ userId }), body);
    return { intent };
  } catch (error) {
    return actionError({
      intent,
      error: formatErrorMessage({
        error,
        defaultMessage: intent === "changeRole" ? "Request to change the platform role failed" : "Request to save labels failed.",
      }),
    });
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
