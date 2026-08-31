import React, { lazy, useState, Suspense } from "react";
import { Button } from "@carbon/react";
import { ArrowRight, ArrowLeft, Close } from "@carbon/react/icons";
import { DelayedRender, Error403, Loading, NotificationsContainer } from "@boomerang-io/carbon-addons-boomerang-react";
import axios from "axios";
import { detect } from "detect-browser";
import { FlagsProvider, useFeature } from "flagged";
import { sortBy } from "lodash";
import Joyride, { CallBackProps, TooltipRenderProps, STATUS } from "react-joyride";
import { useQuery } from "react-query";
import { Outlet, useParams, useRevalidator, useRouteLoaderData } from "react-router-dom";
import type { ShouldRevalidateFunctionArgs } from "react-router-dom";
import ErrorBoundary from "Components/ErrorBoundary";
import ErrorDragon from "Components/ErrorDragon";
import { AppContextProvider, WorkspaceContextProvider, useAppContext } from "State/context";
import SignedOut from "Features/Auth/SignedOut";
import type { AuthConfig } from "Features/Auth/authClient";
import { APP_ROOT, CORE_ENV_URL, FeatureFlag } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl, resolver } from "Config/servicesConfig";
import {
  FlowFeatures,
  FlowNavigationItem,
  FlowWorkspace,
  FlowUser,
  ContextConfig,
  PaginatedResponse,
  WorkflowTemplate,
} from "Types";
import { hasPermission } from "Utils/permissionHelper";
import type { RoutePermissions } from "./AppRoutes";
import Navbar from "./Navbar";
import UnsupportedBrowserPrompt from "./UnsupportedBrowserPrompt";
import styles from "./app.module.scss";

const AppActivation = lazy(() => import("./AppActivation"));

const getUserUrl = serviceUrl.getUserProfile();
const getContextUrl = serviceUrl.getContext();
const getAuthConfigUrl = serviceUrl.getAuthConfig();
const featureFlagsUrl = serviceUrl.getFeatureFlags();
const workflowTemplatesUrl = serviceUrl.template.getWorkflowTemplates();
const browser = detect();
const supportedBrowsers = ["chrome", "firefox", "safari", "edge"];

// --- Root bootstrap loader --------------------------------------------------------------
//
// ssr:true (react-router.config.ts) means route loaders run server-side, in Node, before the
// first render. This file used to gate the whole app behind five client-side react-query
// useQuery calls (profile, context, feature flags, navigation, workflow templates) with an
// isLoading early return - React never runs useEffect during a server render, so those queries
// could never resolve in a single server pass, isLoading stayed true forever, and the server
// sent an empty shell. This loader resolves the same five resources server-side instead.
//
// It's exported from here and re-exported by app/root.tsx (react-router's actual root route
// file - "root" is its well-known route id in framework mode), so it runs ahead of every route.
// App() below reads the result via useRouteLoaderData("root") rather than useLoaderData():
// App is a distinct nested route (registered via layout() in app/routes.ts), and useLoaderData()
// only ever returns the CALLING route's own loader data - this loader belongs to the root route.
export type BootstrapStatus = "ok" | "unauthorized" | "activationRequired";

export type BootstrapData = {
  status: BootstrapStatus;
  user: FlowUser | null;
  context: ContextConfig | null;
  features: FlowFeatures | null;
  navigation: Array<FlowNavigationItem>;
  workflowTemplates: Array<WorkflowTemplate>;
  // GET /auth/config, fetched server-side in the same pass (unauthenticated by design). On a
  // 401 bootstrap it tells SignedOut which sign-in surface to render - server-side, so the
  // real mode is in the SSR HTML; on an authenticated bootstrap the Navbar reads it to decide
  // the Sign Out affordance. `null` means the config could not be loaded, which consumers must
  // treat as "change nothing" (Navbar) or a readable retry surface (SignedOut) - it deliberately
  // does NOT set errorLoading: a broken config endpoint must never take the whole app down.
  authConfig: AuthConfig | null;
  // True when one or more of the resources above failed to load non-fatally - e.g.
  // CORE_SERVICE_INTERNAL_ORIGIN is unconfigured/unreachable (see Config/serverFetch.ts), the
  // common case today. Kept distinct from `status`, which only tracks the profile fetch itself.
  errorLoading: boolean;
};

function emptyBootstrap(status: BootstrapStatus): BootstrapData {
  return {
    status,
    user: null,
    context: null,
    features: null,
    navigation: [],
    workflowTemplates: [],
    authConfig: null,
    errorLoading: false,
  };
}

// Workspace-scoped nav (see getNavigationUrl below) is derived from the path the same way the
// old client-side computation was: /home, /admin/*, and /profile are never workspace-scoped;
// everything else takes its first path segment as the workspace slug.
function resolveWorkspaceName(pathname: string): string | null {
  if (pathname.endsWith("/home") || pathname.startsWith("/admin/") || pathname.endsWith("/profile")) {
    return null;
  }
  return pathname.split("/").filter(Boolean)[0] ?? null;
}

// The incoming Request carries the full path including the app's basename (react-router.config.ts
// - APP_ROOT is the same value); strip it so resolveWorkspaceName sees the same router-relative
// path useLocation().pathname gave the old client-side computation.
function stripAppRoot(pathname: string): string {
  return pathname.startsWith(APP_ROOT) ? (pathname.slice(APP_ROOT.length) || "/") : pathname;
}

/*
 * Wraps a request so it can be started before it is awaited: `settle` never rejects, so a promise
 * created here and abandoned by an early return below cannot become an unhandled rejection.
 * Mirrors Features/WorkflowEditor/editorRoute.ts's helper of the same name.
 */
type Settled<T> = { ok: true; data: T } | { ok: false };

async function settle<T>(promise: Promise<{ data: T }>): Promise<Settled<T>> {
  try {
    return { ok: true, data: (await promise).data };
  } catch (error) {
    return { ok: false };
  }
}

export async function loader({ request }: { request: Request }): Promise<BootstrapData> {
  const api = serverFetch(request);

  /*
   * Started here, awaited further down: neither depends on the user (both were unconditional
   * useQuery calls before this route moved onto the router), so holding them behind the profile
   * fetch adds a round trip to the cold load of EVERY route in the app - this loader blocks the
   * first paint of all of them.
   */
  const featuresPromise = settle(api.get<FlowFeatures>(featureFlagsUrl));
  const templatesPromise = settle(api.get<PaginatedResponse<WorkflowTemplate>>(workflowTemplatesUrl));
  // Unconditional like the two above, and needed on BOTH outcomes of the profile fetch: a 401
  // hands it to SignedOut (which sign-in surface to server-render), an authenticated bootstrap
  // hands it to the Navbar (the Sign Out affordance). This replaced the browser-side
  // GET /auth/config (the retired useAuthConfig hook) - the BFF direction, 2026-09-01.
  const authConfigPromise = settle(api.get<AuthConfig>(getAuthConfigUrl));
  const resolveAuthConfig = async () => {
    const result = await authConfigPromise;
    return result.ok ? result.data : null;
  };

  let user: FlowUser | null = null;
  try {
    const response = await api.get<FlowUser>(getUserUrl);
    user = response.data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 423) {
      return { ...emptyBootstrap("activationRequired"), authConfig: await resolveAuthConfig() };
    }
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      return { ...emptyBootstrap("unauthorized"), authConfig: await resolveAuthConfig() };
    }
    // Any other failure - including an unconfigured/unreachable CORE_SERVICE_INTERNAL_ORIGIN,
    // the common case today - degrades below (errorLoading) rather than failing the render.
  }

  const pathname = stripAppRoot(new URL(request.url).pathname);
  const workspaceName = resolveWorkspaceName(pathname);
  const navigationUrl = serviceUrl.getNavigation({ query: workspaceName ? `?workspace=${workspaceName}` : "" });

  // Genuinely dependent, so these two stay behind the profile fetch: they mirror the old queries'
  // `enabled: Boolean(userQuery.data?.id)` gate - user-scoped, and never fetched without a
  // resolved user (the navigation URL is also workspace-scoped off the request path). `null`
  // means "not attempted", which is not an error.
  const [contextResult, navigationResult] = await Promise.all([
    user?.id ? settle(api.get<ContextConfig>(getContextUrl)) : Promise.resolve(null),
    user?.id ? settle(api.get<Array<FlowNavigationItem>>(navigationUrl)) : Promise.resolve(null),
  ]);
  const [featuresResult, templatesResult] = await Promise.all([featuresPromise, templatesPromise]);

  const features = featuresResult.ok ? featuresResult.data : null;
  const workflowTemplates = templatesResult.ok ? templatesResult.data.content : [];
  const context = contextResult?.ok ? contextResult.data : null;
  const navigation = navigationResult?.ok ? navigationResult.data : [];

  const errorLoading =
    !user ||
    !featuresResult.ok ||
    !templatesResult.ok ||
    Boolean(contextResult && !contextResult.ok) ||
    Boolean(navigationResult && !navigationResult.ok);

  return {
    status: "ok",
    user,
    context,
    features,
    navigation,
    workflowTemplates,
    authConfig: await resolveAuthConfig(),
    errorLoading,
  };
}

// Re-run the whole bootstrap when the workspace segment of the path changes. The old navigation
// query's react-query key included it (getNavigationUrl was recomputed from useLocation().pathname
// on every render), so switching workspaces refetched navigation data scoped to the new one. A
// single root loader can't refetch just one of its five resources, so the granularity is coarser
// than before (all five re-fetch together on a workspace change) - a deliberate trade-off for
// having one loader instead of five independent queries.
export function shouldRevalidate({ currentUrl, nextUrl, defaultShouldRevalidate }: ShouldRevalidateFunctionArgs) {
  const currentWorkspace = resolveWorkspaceName(stripAppRoot(currentUrl.pathname));
  const nextWorkspace = resolveWorkspaceName(stripAppRoot(nextUrl.pathname));
  return currentWorkspace !== nextWorkspace || defaultShouldRevalidate;
}

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
export function ProtectedRoute({ allowed, children }: { allowed: boolean; children: React.ReactNode }) {
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

// ContextService.getHeaderNavigation() (service-core) returns null by design whenever
// UserService.getCurrentUser() can't resolve a real user record for the current principal - the
// documented case being the UnauthenticatedGlobalToken installed when flow.security.enabled=false
// (see UserService.getCurrentUser()'s Javadoc: "callers that can gracefully render 'no current
// user' (ContextService.getHeaderNavigation) already null-check"). That 200-with-null response
// makes contextResult.ok true but contextResult.data null, so it does NOT set errorLoading - this
// is a legitimate degrade, not a failure. This default lets Navbar/UIShell (both of which require
// a real ContextConfig - contextData.platform is destructured unconditionally) render the shell
// instead of App() falling through to `return null` and leaving every route permanently blank.
const DEFAULT_CONTEXT: ContextConfig = {
  features: {
    "consent.enabled": false,
    "docs.enabled": false,
    "metering.enabled": false,
    "notifications.enabled": false,
    "support.enabled": false,
    "welcome.enabled": false,
  },
  navigation: [],
  platform: {
    baseEnvUrl: CORE_ENV_URL,
    baseServicesUrl: CORE_ENV_URL,
    displayLogo: false,
    name: "Boomerang Flow",
    platformName: "Boomerang",
    privateWorkspaces: false,
    sendMail: false,
    signOutUrl: "",
    version: "",
  },
  platformMessage: {
    kind: "",
    message: "",
    title: "",
  },
};

export default function App() {
  const bootstrap = useRouteLoaderData<BootstrapData>("root");
  const revalidator = useRevalidator();

  const [shouldShowBrowserWarning, setShouldShowBrowserWarning] = useState(
    !supportedBrowsers.includes(browser?.name ?? ""),
  );
  const [isTutorialActive, setIsTutorialActive] = useState(false);
  const [showActivatePlatform, setShowActivatePlatform] = React.useState(bootstrap?.status === "activationRequired");
  const [activationCode, setActivationCode] = React.useState<string>();

  const handleSetActivationCode = (code: string) => {
    setActivationCode(code);
    setShowActivatePlatform(false);
    // The root loader owns the profile fetch now (no react-query cache to invalidate) -
    // revalidate re-runs it so the freshly-activated user comes back on the next render.
    revalidator.revalidate();
  };

  // Defensive only: the root loader (app/root.tsx, implemented above) never throws - it always
  // resolves a full BootstrapData - so this branch is unreached in practice. It exists because
  // useRouteLoaderData's return type includes `undefined`; falling through to `null` here would
  // reproduce exactly the blank-page bug this loader exists to fix.
  if (!bootstrap) {
    return <ErrorDragon style={{ margin: "5rem 0" }} />;
  }

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

  // Distinguishable from the generic degraded-load path below: a 401 means this specific
  // request isn't authenticated (an expired/absent session), not that a resource failed to
  // load. Previously this was silently swallowed into `undefined` user data, which fell through
  // every render branch to `return null` - a blank page with no signal why. SignedOut owns what
  // happens next per the authConfig this loader fetched in the same pass: nothing extra (none),
  // one silent exchange via the /auth/signin action (proxy), or a Sign in button starting the
  // server-side PKCE flow (oidc) - see Features/Auth. onReloadConfig re-runs this loader, which
  // owns the config fetch now.
  if (bootstrap.status === "unauthorized") {
    return <SignedOut config={bootstrap.authConfig} onReloadConfig={() => revalidator.revalidate()} />;
  }

  if (bootstrap.errorLoading) {
    return <ErrorDragon style={{ margin: "5rem 0" }} />;
  }

  // User and features are guaranteed truthy here: errorLoading (handled above) is already true
  // whenever `!user` or `!featuresResult.ok`. Context is NOT included in that guarantee - a
  // successful-but-null getHeaderNavigation() response (see DEFAULT_CONTEXT above) leaves
  // errorLoading false with bootstrap.context still null, so it falls back to the default here
  // rather than gating the whole app render.
  if (bootstrap.user && bootstrap.features) {
    const feature = bootstrap.features.features;
    const contextData = bootstrap.context ?? DEFAULT_CONTEXT;
    return (
      <FlagsProvider features={buildFeatureFlags(feature)}>
        <Navbar
          flowNavigationData={bootstrap.navigation}
          handleOnTutorialClick={() => setIsTutorialActive(true)}
          contextData={contextData}
          userData={bootstrap.user}
        />
        {
          //<OnBoardExpContainer isTutorialActive={isTutorialActive} setIsTutorialActive={setIsTutorialActive} />
        }
        <ErrorBoundary>
          <Main
            isTutorialActive={isTutorialActive}
            contextData={contextData}
            setIsTutorialActive={setIsTutorialActive}
            setShouldShowBrowserWarning={setShouldShowBrowserWarning}
            shouldShowBrowserWarning={shouldShowBrowserWarning}
            userData={bootstrap.user}
            workflowTemplatesData={bootstrap.workflowTemplates}
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

  // The route tree itself now lives in the router config (AppRoutes.tsx / Root.tsx) so that
  // loaders/actions can attach to it. These permission/feature-flag gates depend on hooks
  // (useAppContext, useFeature) that only resolve inside this layout, so they're computed
  // once here and handed down through the outlet context; route elements read the specific
  // flag they need back out via useRoutePermissions().
  const routePermissions: RoutePermissions = {
    canReadSettings,
    canReadParameters,
    canReadWorkflowTemplates,
    canReadTasks,
    canReadTokens,
    canReadWorkspaces,
    canReadUsers,
    activityEnabled: Boolean(activityEnabled),
    insightsEnabled: Boolean(insightsEnabled),
    workspaceParametersEnabled: Boolean(workspaceParametersEnabled),
  };

  return (
    <main id="content" className={styles.container}>
      <Suspense
        fallback={
          <DelayedRender>
            <Loading />
          </DelayedRender>
        }
      >
        <Outlet context={routePermissions} />
        <Tutorial />
      </Suspense>
      <NotificationsContainer enableMultiContainer />
    </main>
  );
});

// Used both directly by App's own layout gating above and by route elements defined in
// AppRoutes.tsx (imported from there) to resolve the active workspace from the `:workspace`
// path param before rendering a workspace-scoped feature.
export function WorkspaceContainer(props: { children: React.ReactNode }) {
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
