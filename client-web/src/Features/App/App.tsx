import React, { lazy, useState, Suspense } from "react";
import { Button } from "@carbon/react";
import { ArrowRight, ArrowLeft, Close } from "@carbon/react/icons";
import { DelayedRender, Error403, Error404, Loading, NotificationsContainer } from "@boomerang-io/carbon-addons-boomerang-react";
import axios from "axios";
import { detect } from "detect-browser";
import { FlagsProvider, useFeature } from "flagged";
import { sortBy } from "lodash";
import Joyride, { CallBackProps, TooltipRenderProps, STATUS } from "react-joyride";
import { useQuery, useQueryClient } from "react-query";
import { Navigate, Route, Routes, useLocation, useParams } from "react-router-dom";
import ErrorBoundary from "Components/ErrorBoundary";
import ErrorDragon from "Components/ErrorDragon";
import { AppContextProvider, WorkspaceContextProvider, useAppContext } from "State/context";
import { AppPath, FeatureFlag } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowFeatures, FlowNavigationItem, FlowWorkspace, FlowUser, ContextConfig, WorkflowTemplate } from "Types";
import { hasPermission } from "Utils/permissionHelper";
import Navbar from "./Navbar";
import UnsupportedBrowserPrompt from "./UnsupportedBrowserPrompt";
import styles from "./app.module.scss";

const AppActivation = lazy(() => import("./AppActivation"));
const Activity = lazy(() => import("Features/Activity"));
const Actions = lazy(() => import("Features/Actions"));
const Editor = lazy(() => import("Features/WorkflowEditor"));
const Execution = lazy(() => import("Features/WorkflowRun"));
const GlobalParameters = lazy(() => import("Features/Parameters/GlobalParameters"));
const Tokens = lazy(() => import("Features/GlobalTokens/GlobalTokens"));
const Insights = lazy(() => import("Features/Insights"));
const Integrations = lazy(() => import("Features/Integrations"));
const Schedules = lazy(() => import("Features/Schedules"));
const Settings = lazy(() => import("Features/Settings"));
const TemplateWorkflows = lazy(() => import("Features/TemplateWorkflows"));
const Workspaces = lazy(() => import("Features/Workspaces"));
const ManageWorkspace = lazy(() => import("Features/WorkspaceDetailed"));
const WorkspaceParameters = lazy(() => import("Features/Parameters/WorkspaceParameters"));
const WorkspaceTasks = lazy(() => import("Features/TaskManager/WorkspaceTasks"));
const AdminTasks = lazy(() => import("Features/TaskManager/AdminTasks"));
const Users = lazy(() => import("Features/Users"));
const UserProfile = lazy(() => import("Features/UserProfile"));
const Workflows = lazy(() => import("Features/Workflows"));
const Home = lazy(() => import("Features/Home"));

const getUserUrl = serviceUrl.getUserProfile();
const getContextUrl = serviceUrl.getContext();
const featureFlagsUrl = serviceUrl.getFeatureFlags();
const workflowTemplatesUrl = serviceUrl.template.getWorkflowTemplates();
const browser = detect();
const supportedBrowsers = ["chrome", "firefox", "safari", "edge"];

// Maps the /features response (settings-value keys, e.g. "workspace.management") onto the
// FlagsProvider prop names (FeatureFlag, e.g. "WorkspaceManagementEnabled") consumed via
// useFeature() throughout the app. Exported so a test can assert every flag resolves to a
// boolean rather than silently going `undefined` when the two key sets drift apart.
export function buildFeatureFlags(feature: FlowFeatures["features"]) {
  return {
    ActivityEnabled: feature["activity"],
    EditVerifiedTasksEnabled: feature["enable.verified.tasks.edit"],
    GlobalParametersEnabled: feature["global.parameters"],
    InsightsEnabled: feature["insights"],
    WorkspaceManagementEnabled: feature["workspace.management"],
    WorkspaceParametersEnabled: feature["workspace.parameters"],
    WorkspaceTasksEnabled: feature["workspace.tasks"],
    UserManagementEnabled: feature["user.management"],
    WorkspaceQuotasEnabled: feature["workspace.quotas"],
    WorkflowTokensEnabled: feature["workflow.tokens"],
    WorkflowTriggersEnabled: feature["workflow.triggers"],
  };
}

// react-router-dom v6+ dropped v5's <Route> children/component render props that the
// wrapper's ProtectedRoute relied on, so it can no longer be used standalone here. This is
// a direct, minimal replacement of the same gating: render the guarded element when allowed,
// otherwise the same Error403 the wrapper rendered.
function ProtectedRoute({ allowed, children }: { allowed: boolean; children: React.ReactNode }) {
  if (!allowed) {
    return (
      <Error403
        message="If you think you should be, contact your friendly neighborhood platform admin."
        title="Sorry mate, you are not allowed here."
      />
    );
  }
  return <>{children}</>;
}

export default function App() {
  const location = useLocation();
  const queryClient = useQueryClient();
  const workspaceName =
    location.pathname.endsWith("/home") ||
    location.pathname.startsWith("/admin/") ||
    location.pathname.endsWith("/profile")
      ? null
      : location.pathname.split("/").filter(Boolean)[0];
  const query = workspaceName ? `?workspace=${workspaceName}` : "";
  const getNavigationUrl = serviceUrl.getNavigation({ query });

  const [shouldShowBrowserWarning, setShouldShowBrowserWarning] = useState(
    !supportedBrowsers.includes(browser?.name ?? ""),
  );
  const [isTutorialActive, setIsTutorialActive] = useState(false);
  const [showActivatePlatform, setShowActivatePlatform] = React.useState(false);
  const [activationCode, setActivationCode] = React.useState<string>();

  const fetchUserResolver = async () => {
    try {
      const response = await axios.get(getUserUrl);
      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error))
        if (error.response?.status === 423) {
          // Prevent both the rerender and remount on refetch
          if (!showActivatePlatform && !activationCode) {
            setShowActivatePlatform(true);
          }
          return {};
        }
    }
  };

  const featureQuery = useQuery<FlowFeatures, string>({
    queryKey: featureFlagsUrl,
    queryFn: resolver.query(featureFlagsUrl),
  });

  const userQuery = useQuery<FlowUser, string>({
    queryKey: getUserUrl,
    queryFn: fetchUserResolver,
  });

  const contextQuery = useQuery<ContextConfig, string>({
    queryKey: getContextUrl,
    queryFn: resolver.query(getContextUrl),
    enabled: Boolean(userQuery.data?.id),
  });

  const navigationQuery = useQuery<Array<FlowNavigationItem>, string>({
    queryKey: getNavigationUrl,
    queryFn: resolver.query(getNavigationUrl),
    enabled: Boolean(userQuery.data?.id),
  });

  const workflowTemplatesQuery = useQuery({
    queryKey: workflowTemplatesUrl,
    queryFn: resolver.query(workflowTemplatesUrl),
  });

  const isLoading =
    userQuery.isLoading ||
    contextQuery.isLoading ||
    featureQuery.isLoading ||
    navigationQuery.isLoading ||
    workflowTemplatesQuery.isLoading;

  const hasError =
    userQuery.isError ||
    contextQuery.isError ||
    featureQuery.isError ||
    navigationQuery.isError ||
    workflowTemplatesQuery.isError;

  const handleSetActivationCode = (code: string) => {
    setActivationCode(code);
    setShowActivatePlatform(false);
    queryClient.invalidateQueries(getUserUrl);
  };

  // Check if the app is Activated prior to error checking
  if (showActivatePlatform) {
    return (
      <Suspense fallback={<DelayedRender>{null}</DelayedRender>}>
        <div className={styles.appActivationContainer}>
          <AppActivation setActivationCode={handleSetActivationCode} />
        </div>
      </Suspense>
    );
  }

  if (isLoading) {
    return (
      <DelayedRender>
        <Loading />
      </DelayedRender>
    );
  }

  if (hasError) {
    return <ErrorDragon style={{ margin: "5rem 0" }} />;
  }

  // Context Data needed for the app to render
  if (userQuery.data && contextQuery.data && featureQuery.data && navigationQuery.data) {
    const feature = featureQuery.data.features;
    return (
      <FlagsProvider features={buildFeatureFlags(feature)}>
        <Navbar
          flowNavigationData={navigationQuery.data}
          handleOnTutorialClick={() => setIsTutorialActive(true)}
          contextData={contextQuery.data}
          userData={userQuery.data}
        />
        {
          //<OnBoardExpContainer isTutorialActive={isTutorialActive} setIsTutorialActive={setIsTutorialActive} />
        }
        <ErrorBoundary>
          <Main
            isTutorialActive={isTutorialActive}
            contextData={contextQuery.data}
            setIsTutorialActive={setIsTutorialActive}
            setShouldShowBrowserWarning={setShouldShowBrowserWarning}
            shouldShowBrowserWarning={shouldShowBrowserWarning}
            userData={userQuery.data}
            workflowTemplatesData={workflowTemplatesQuery.data.content}
          />
        </ErrorBoundary>
      </FlagsProvider>
    );
  }
  return null;
}

interface MainProps {
  isTutorialActive: boolean;
  contextData: ContextConfig;
  setIsTutorialActive: (isTutorialActive: boolean) => void;
  setShouldShowBrowserWarning: (shouldShowBrowserWarning: boolean) => void;
  shouldShowBrowserWarning: boolean;
  userData: FlowUser;
  workflowTemplatesData: Array<WorkflowTemplate>;
}

function Main({
  isTutorialActive,
  contextData,
  setIsTutorialActive,
  setShouldShowBrowserWarning,
  shouldShowBrowserWarning,
  userData,
  workflowTemplatesData,
}: MainProps) {
  const { id: userId } = userData;

  // Don't show anything to a user that doesn't exist, the UIShell will show the redirect
  if (!userId) {
    return null;
  }

  if (shouldShowBrowserWarning) {
    return <UnsupportedBrowserPrompt onDismissWarning={() => setShouldShowBrowserWarning(false)} />;
  }

  return (
    <AppContextProvider
      value={{
        isTutorialActive,
        setIsTutorialActive,
        communityUrl: contextData?.platform?.communityUrl ?? "",
        name: contextData?.platform?.name ?? "",
        workspaces: sortBy(userData.teams, "name"),
        user: userData,
        workflowTemplates: workflowTemplatesData,
      }}
    >
      <AppFeatures />
    </AppContextProvider>
  );
}

const AppFeatures = React.memo(function AppFeatures() {
  const { user } = useAppContext();
  const activityEnabled = useFeature(FeatureFlag.ActivityEnabled);
  const insightsEnabled = useFeature(FeatureFlag.InsightsEnabled);
  const workspaceParametersEnabled = useFeature(FeatureFlag.WorkspaceParametersEnabled);
  //const workspaceTasksEnabled = useFeature(FeatureFlag.WorkspaceTasksEnabled);

  // The admin section is gated on real grants, not a role guess: each screen requires
  // the global read/write it actually needs, resolved from the profile's permission list.
  const canReadSettings = hasPermission(user, "system", "read");
  const canReadParameters = hasPermission(user, "parameter", "read");
  const canReadWorkflowTemplates = hasPermission(user, "workflowtemplate", "read");
  const canReadTasks = hasPermission(user, "task", "read");
  const canReadTokens = hasPermission(user, "token", "read");
  const canReadWorkspaces = hasPermission(user, "workspace", "read");
  const canReadUsers = hasPermission(user, "user", "read");

  return (
    <main id="content" className={styles.container}>
      <Suspense
        fallback={
          <DelayedRender>
            <Loading />
          </DelayedRender>
        }
      >
        <Routes>
          <Route path="/home" element={<Home />} />
          <Route path="/profile" element={<UserProfile />} />
          <Route path={AppPath.Settings} element={<ProtectedRoute allowed={canReadSettings}><Settings /></ProtectedRoute>} />
          <Route
            path={AppPath.Properties}
            element={
              <ProtectedRoute allowed={canReadParameters}>
                <GlobalParameters />
              </ProtectedRoute>
            }
          />
          <Route
            path={AppPath.TemplateWorkflows}
            element={
              <ProtectedRoute allowed={canReadWorkflowTemplates}>
                <TemplateWorkflows />
              </ProtectedRoute>
            }
          />
          <Route
            path={`${AppPath.Tasks}/*`}
            element={
              <ProtectedRoute allowed={canReadTasks}>
                <AdminTasks />
              </ProtectedRoute>
            }
          />
          <Route path={AppPath.Tokens} element={<ProtectedRoute allowed={canReadTokens}><Tokens /></ProtectedRoute>} />
          <Route
            path={AppPath.WorkspaceList}
            element={
              <ProtectedRoute allowed={canReadWorkspaces}>
                <Workspaces />
              </ProtectedRoute>
            }
          />
          <Route
            path={`${AppPath.UserList}/*`}
            element={
              <ProtectedRoute allowed={canReadUsers}>
                <Users />
              </ProtectedRoute>
            }
          />
          <Route
            path={AppPath.Run}
            element={
              <WorkspaceContainer>
                <ProtectedRoute allowed={Boolean(activityEnabled)}>
                  <Execution />
                </ProtectedRoute>
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.Activity}
            element={
              <WorkspaceContainer>
                <ProtectedRoute allowed={Boolean(activityEnabled)}>
                  <Activity />
                </ProtectedRoute>
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.Insights}
            element={
              <WorkspaceContainer>
                <ProtectedRoute allowed={Boolean(insightsEnabled)}>
                  <Insights />
                </ProtectedRoute>
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.ManageWorkspaceParameters}
            element={
              <WorkspaceContainer>
                <ProtectedRoute allowed={Boolean(workspaceParametersEnabled)}>
                  <WorkspaceParameters />
                </ProtectedRoute>
              </WorkspaceContainer>
            }
          />
          <Route
            path={`${AppPath.ManageWorkspace}/*`}
            element={
              <WorkspaceContainer>
                <ManageWorkspace />
              </WorkspaceContainer>
            }
          />
          <Route
            path={`${AppPath.Actions}/*`}
            element={
              <WorkspaceContainer>
                <Actions />
              </WorkspaceContainer>
            }
          />
          <Route
            path={`${AppPath.Editor}/*`}
            element={
              <WorkspaceContainer>
                <Editor />
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.Schedules}
            element={
              <WorkspaceContainer>
                <Schedules />
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.Workflows}
            element={
              <WorkspaceContainer>
                <Workflows />
              </WorkspaceContainer>
            }
          />
          <Route
            path={AppPath.Integrations}
            element={
              <WorkspaceContainer>
                <Integrations />
              </WorkspaceContainer>
            }
          />
          <Route
            path={`${AppPath.ManageTasks}/*`}
            element={
              <WorkspaceContainer>
                <WorkspaceTasks />
              </WorkspaceContainer>
            }
          />
          <Route path="/" element={<Navigate to="/home" replace />} />
          {/* Any other single-segment path is treated as a workspace slug (matching the
          pre-migration behaviour); deeper unmatched sub-paths 404 inside that workspace. */}
          <Route
            path="/:workspace/*"
            element={
              <WorkspaceContainer>
                <Error404 theme="boomerang" />
              </WorkspaceContainer>
            }
          />
        </Routes>
        <Tutorial />
      </Suspense>
      <NotificationsContainer enableMultiContainer />
    </main>
  );
});

function WorkspaceContainer(props: { children: React.ReactNode }) {
  const { workspace = "" } = useParams<{ workspace: string }>();
  const getWorkspaceUrl = serviceUrl.resourceWorkspace({ workspace });

  const workspaceQuery = useQuery<FlowWorkspace>({
    queryKey: getWorkspaceUrl,
    queryFn: resolver.query(getWorkspaceUrl),
  });

  if (workspaceQuery.isLoading || workspaceQuery.error) {
    return null;
  }

  if (workspaceQuery.data) {
    return (
      <WorkspaceContextProvider
        value={{
          workspace: workspaceQuery.data,
        }}
      >
        {props.children}
      </WorkspaceContextProvider>
    );
  }

  return null;
}

/**
 * TODO: MOVE THIS TO OWN COMPONENT
 * AND DETERMINE WHEN TO RENDER WHICH TUTORIAL
 * BASED ON THE PATH
 */
// const home_steps = [
//   {
//     disableBeacon: true,
//     target: "#your-workspaces",
//     content: "This is my awesome feature!",
//   },
//   {
//     disableBeacon: true,
//     target: "#explore",
//     content: "This is my awesome again!",
//   },
// ];

const workflows_steps = [
  {
    disableBeacon: true,
    target: "#my-workflows",
    content: "This is my awesome feature!",
  },
];

// const stepMapper = {
//   ":workspace/workflows": workflows_steps,
// };

function Tutorial() {
  const { setIsTutorialActive, isTutorialActive } = useAppContext();
  const handleJoyrideCallback = (data: CallBackProps) => {
    const { action, status } = data;

    if (action === "close") {
      setIsTutorialActive(false);
    }

    if (status === STATUS.FINISHED || status === STATUS.SKIPPED) {
      setIsTutorialActive(false);
    }
  };

  if (!isTutorialActive) {
    return null;
  }

  return (
    <Joyride
      continuous
      callback={handleJoyrideCallback}
      steps={workflows_steps}
      tooltipComponent={({
        continuous,
        index,
        step,
        backProps,
        primaryProps,
        closeProps,
        tooltipProps,
      }: TooltipRenderProps) => {
        return (
          <div
            {...tooltipProps}
            style={{
              background: "white",
              padding: "1rem",
              borderRadius: "0.25rem",
              height: "10rem",
              width: "20rem",
            }}
          >
            {step.title && <h2>{step.title}</h2>}
            <div>{step.content}</div>
            <footer>
              {index > 0 && (
                <Button {...backProps} renderIcon={ArrowLeft} size="sm" kind="secondary">
                  Back
                </Button>
              )}
              {continuous && (
                <Button {...primaryProps} renderIcon={ArrowRight} size="sm">
                  Next
                </Button>
              )}
              <Button {...closeProps} hasIconOnly renderIcon={Close} iconDescription="Close" size="sm">
                Close
              </Button>
            </footer>
          </div>
        );
      }}
    />
  );
}
