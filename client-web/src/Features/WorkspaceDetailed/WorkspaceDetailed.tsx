import React from "react";
import {
  ErrorMessage,
  FeatureHeader,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { Helmet } from "react-helmet";
import { Outlet, useLoaderData, useOutletContext } from "react-router-dom";
import { Box } from "reflexbox";
import { useAppContext } from "Hooks";
import { FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowUser, FlowWorkspace } from "Types";
import Header from "./Header";
import styles from "./workspaceDetailed.module.scss";

// Layout route for the Manage Workspace tabs (app/routes/manageWorkspace.tsx + the nested
// entries under it in app/routes.ts). This used to be a single "/*" splat route that fetched the
// workspace with useQuery and then switched on an inner <Routes>; each tab is now a real nested
// route with its own loader/action, and this file only owns the one read they all share - the
// workspace record itself, which backs the header and is the sole data source for the Members,
// Approver Groups, Quotas and Settings tabs.
//
// Server loader (see CLAUDE.md's client-web SSR direction), following
// Features/Parameters/GlobalParameters/GlobalParameters.tsx: serverFetch(request) rather than the
// browser `resolver`/`axios` instance, and a failed fetch resolves with an error flag instead of
// throwing, so the page chrome still renders and only the body shows the error - matching the
// previous `workspaceDetailsQuery.error` branch.

type LoaderData = {
  workspace: FlowWorkspace | null;
  errorLoading: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get(
      serviceUrl.resourceWorkspace({ workspace: String(params.workspace) }),
    );
    return { workspace: response.data, errorLoading: false };
  } catch (error) {
    return { workspace: null, errorLoading: true };
  }
}

/*
 * The Settings tab's intents (see ./Settings/Settings.tsx, which owns the action they post to).
 * They are named for the workspace-level operation they perform rather than the bare verb,
 * because the tabs under this route tree all submit to the same matched route and several of
 * them have their own "delete": the Approver Groups tab deletes a GROUP and the Tokens tab
 * deletes a TOKEN. When this route's shouldRevalidate below keyed off the literal string
 * "delete", an approver-group delete was silently suppressed along with the workspace one - the
 * DELETE succeeded server-side and the row stayed on screen until the user navigated away.
 */
export const WorkspaceIntent = {
  Rename: "renameWorkspace",
  Delete: "deleteWorkspace",
  UpdateLabels: "updateWorkspaceLabels",
} as const;

/*
 * The two intents above that make this route's own loader unrunnable, and only those two.
 */
const SUPPRESS_REVALIDATION_INTENTS: ReadonlyArray<string> = [WorkspaceIntent.Rename, WorkspaceIntent.Delete];

/*
 * A fetcher submission revalidates every matched loader by default, which is what keeps the tabs
 * in sync after a write. Two of the Settings tab's intents are the exception: a rename changes
 * the `:workspace` slug and a delete removes the record outright, so re-running THIS loader
 * against the URL still in the address bar fetches a workspace that no longer exists there. It
 * 404s, this route swaps its <Outlet> for <ErrorMessage>, and that unmounts the very component
 * whose effect was about to navigate to the right place - leaving the user on an error page with
 * no toast. Skipping revalidation lets that navigation happen; the destination loads fresh data
 * on arrival, and the root loader (the user's workspace list) still revalidates either way.
 *
 * Every other write on every other tab MUST revalidate: the Members, Approver Groups and Quotas
 * tabs all render straight off this loader's workspace record, so suppressing them leaves the
 * screen showing data the server no longer has.
 */
export function shouldRevalidate({
  formData,
  defaultShouldRevalidate,
}: {
  formData?: FormData;
  defaultShouldRevalidate: boolean;
}): boolean {
  const intent = formData?.get("intent");
  if (typeof intent === "string" && SUPPRESS_REVALIDATION_INTENTS.includes(intent)) {
    return false;
  }
  return defaultShouldRevalidate;
}

/**
 * Handed to every tab through <Outlet context>. `canEdit` and `user` are client-only derivations
 * (a feature flag and AppContext), so they're computed here once rather than in each tab.
 */
export type WorkspaceDetailedContext = {
  workspace: FlowWorkspace;
  canEdit: boolean;
  user: FlowUser;
};

export function useWorkspaceDetailedContext() {
  return useOutletContext<WorkspaceDetailedContext>();
}

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
  const { workspace, errorLoading } = useLoaderData() as LoaderData;
  const workspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);
  const { user } = useAppContext();

  // No loading branch: the router resolves this route's loader before rendering it, so there is
  // no in-component pending state to show (the previous workspaceDetailsQuery.isLoading case).
  if (errorLoading || !workspace) {
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  const canEdit = Boolean(workspaceManagementEnabled) && workspace.status === "active";
  const context: WorkspaceDetailedContext = { workspace, canEdit, user };

  return (
    <div className={styles.container}>
      <Header workspace={workspace} />
      <Outlet context={context} />
    </div>
  );
}

export default WorkspaceDetailedContainer;
