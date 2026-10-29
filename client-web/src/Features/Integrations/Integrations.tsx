import React from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  Error,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useNavigate, Link, useLoaderData } from "react-router-dom";
import IntegrationCard from "Components/IntegrationCard";
import { useWorkspaceContext } from "Hooks";
import { actionError, type ActionError } from "Utils/actionResult";
import { appLink } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { FlowWorkspace, Integration } from "Types";
import styles from "./integrations.module.scss";

// Route module: this file's `loader`/`action` are re-exported from app/routes/integrations.tsx
// (path="/:workspace/integrations") - see Features/Parameters/GlobalParameters/GlobalParameters.tsx
// for the fuller rationale on the split and the serverFetch/errorLoading/ssr:true contract.
//
// The workspace-scoped fetch below uses `params.workspace` (the route param, available to the
// loader directly) rather than `useWorkspaceContext()`, which resolves through
// app/routes/workspaceLayout.tsx's loader - see WorkspaceParameters.tsx/WorkspaceTasks.tsx for
// the same split (loader fetches by param, component still reads the context object for display
// concerns like the breadcrumb/header).
type LoaderData = {
  integrations: Array<Integration>;
  errorLoading: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  try {
    const response = await serverFetch(request).get(serviceUrl.getIntegrations({ workspace }));
    return { integrations: response.data, errorLoading: false };
  } catch (error) {
    return { integrations: [], errorLoading: true };
  }
}

type ActionIntent =
  | { intent: "connect"; name: string }
  | { intent: "disconnect"; name: string }
  | { intent: "share"; name: string; shared: boolean };
export type ActionResult = ActionIntent | (ActionIntent & ActionError);

// Each integration type unlinks through its own endpoint, keyed by the integration's display
// name - mirrors the previous browser-side `unlinkResolverByIntegrationName` lookup in
// IntegrationCard.tsx, moved server-side now that the mutation is an action. An integration name
// with no entry here fails informatively instead of silently calling the wrong (or an undefined)
// resolver.
const unlinkUrlByIntegrationName: Record<string, () => string> = {
  GitHub: serviceUrl.postGitHubAppUnlink,
  Slack: serviceUrl.postSlackUnlink,
};

// Sharing an integration's credentials with tasks, by display name.
const shareUrlByIntegrationName: Record<string, () => string> = {
  GitHub: () => serviceUrl.postIntegrationShare({ integration: "github" }),
  Slack: () => serviceUrl.postIntegrationShare({ integration: "slack" }),
};

// No explicit return-type annotation: `actionError` wraps the failure branches in RR's `data()`
// (see Utils/actionResult.ts), so the function's real return type is a union of the plain
// success payload and `DataWithResponseInit<...>` - React Router unwraps the latter at runtime,
// and `ActionResult` above is the already-unwrapped shape `fetcher.data` is typed against.
export async function action({ request }: { request: Request }) {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));
  const name = String(formData.get("name"));

  // A form integration is connected, edited and disconnected through its share route by type: the
  // values the workspace entered are what it shares.
  if (formData.get("kind") === "form") {
    const shareUrl = serviceUrl.postIntegrationShare({ integration: String(formData.get("type")) });
    const workspace = String(formData.get("workspace"));
    const connecting = intent === "connect";
    try {
      await serverFetch(request).post(
        shareUrl,
        connecting
          ? { workspace, shared: true, values: JSON.parse(String(formData.get("values") ?? "{}")) }
          : { workspace, shared: false },
      );
      return connecting ? { intent: "connect" as const, name } : { intent: "disconnect" as const, name };
    } catch (error) {
      return actionError({
        intent: connecting ? ("connect" as const) : ("disconnect" as const),
        name,
        error: formatErrorMessage({
          error,
          defaultMessage: connecting ? `Request to connect ${name} failed` : `Request to disconnect ${name} failed`,
        }),
      });
    }
  }

  if (intent === "disconnect") {
    const getUnlinkUrl = unlinkUrlByIntegrationName[name];
    if (!getUnlinkUrl) {
      return actionError({
        intent: "disconnect",
        name,
        error: { title: "Something's Wrong", message: `No disconnect handler is registered for ${name}` },
      });
    }
    const workspace = String(formData.get("workspace"));
    const ref = String(formData.get("ref"));
    try {
      await serverFetch(request).post(getUnlinkUrl(), { workspace, ref });
      return { intent: "disconnect", name };
    } catch (error) {
      return actionError({
        intent: "disconnect",
        name,
        error: formatErrorMessage({ error, defaultMessage: `Request to disable ${name.toLowerCase()} failed` }),
      });
    }
  }

  if (intent === "share") {
    const shared = formData.get("shared") === "true";
    const getShareUrl = shareUrlByIntegrationName[name];
    if (!getShareUrl) {
      return actionError({
        intent: "share",
        name,
        shared,
        error: { title: "Something's Wrong", message: `${name} has no credentials to share` },
      });
    }
    try {
      await serverFetch(request).post(getShareUrl(), { workspace: String(formData.get("workspace")), shared });
      return { intent: "share", name, shared };
    } catch (error) {
      return actionError({
        intent: "share",
        name,
        shared,
        error: formatErrorMessage({ error, defaultMessage: `Request to change sharing for ${name} failed` }),
      });
    }
  }

  return actionError({ intent: "disconnect", name, error: { title: "Something's Wrong", message: "Unknown action" } });
}

export default function Integrations() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const { integrations, errorLoading } = useLoaderData() as LoaderData;

  // TODO: make this smarter bc we shouldn't get to the route without an active workspace
  if (!workspace) {
    navigate(appLink.home());
    return null;
  }

  return (
    <Layout workspace={workspace}>
      {errorLoading ? (
        <Error />
      ) : (
        <div className={styles.workflows}>
          {integrations.map((template: Integration) => (
            <IntegrationCard key={template.id} workspaceName={workspace.name} data={template} />
          ))}
        </div>
      )}
    </Layout>
  );
}

interface LayoutProps {
  workspace: FlowWorkspace;
  children: React.ReactNode;
}

function Layout(props: LayoutProps) {
  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{props.workspace.name}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  return (
    <div className={styles.container}>
      <Header
        className={styles.header}
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Integrations</HeaderTitle>
            <HeaderSubtitle>Connect the tools this workspace’s workflows work with.</HeaderSubtitle>
          </>
        }
      />
      <div aria-label="My Integrations" className={styles.content} role="region" id="my-integrations">
        <section className={styles.sectionContainer}>{props.children}</section>
      </div>
    </div>
  );
}
