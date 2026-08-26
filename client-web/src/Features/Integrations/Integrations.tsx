import React from "react";
import { useNavigate, Link, useLoaderData } from "react-router-dom";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  Error,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import IntegrationCard from "Components/IntegrationCard";
import { useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowWorkspace, Integration } from "Types";
import styles from "./integrations.module.scss";

// Route module: this file's `loader`/`action` are re-exported from app/routes/integrations.tsx
// (path="/:workspace/integrations") - see Features/Parameters/GlobalParameters/GlobalParameters.tsx
// for the fuller rationale on the split and the serverFetch/errorLoading/ssr:true contract.
//
// The workspace-scoped fetch below uses `params.workspace` (the route param, available to the
// loader directly) rather than `useWorkspaceContext()`, which only resolves client-side via
// WorkspaceContainer's own react-query call - see WorkspaceParameters.tsx/WorkspaceTasks.tsx for
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

export type ActionResult =
  | { ok: true; intent: "disconnect"; name: string }
  | { ok: false; intent: "disconnect"; name: string; errorMessage: { title: string; message: string } };

// Each integration type unlinks through its own endpoint, keyed by the integration's display
// name - mirrors the previous browser-side `unlinkResolverByIntegrationName` lookup in
// IntegrationCard.tsx, moved server-side now that the mutation is an action. Only GitHub has a
// disconnect endpoint today; an integration name with no entry here fails informatively instead
// of silently calling the wrong (or an undefined) resolver.
const unlinkUrlByIntegrationName: Record<string, () => string> = {
  GitHub: serviceUrl.postGitHubAppUnlink,
};

export async function action({ request }: { request: Request }): Promise<ActionResult> {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));
  const name = String(formData.get("name"));

  if (intent === "disconnect") {
    const getUnlinkUrl = unlinkUrlByIntegrationName[name];
    if (!getUnlinkUrl) {
      return {
        ok: false,
        intent: "disconnect",
        name,
        errorMessage: { title: "Something's Wrong", message: `No disconnect handler is registered for ${name}` },
      };
    }
    const workspace = String(formData.get("workspace"));
    const ref = String(formData.get("ref"));
    try {
      await serverFetch(request).post(getUnlinkUrl(), { workspace, ref });
      return { ok: true, intent: "disconnect", name };
    } catch (error) {
      return {
        ok: false,
        intent: "disconnect",
        name,
        errorMessage: formatErrorMessage({ error, defaultMessage: `Request to disable ${name.toLowerCase()} failed` }),
      };
    }
  }

  return {
    ok: false,
    intent: "disconnect",
    name,
    errorMessage: { title: "Something's Wrong", message: "Unknown action" },
  };
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
            <HeaderSubtitle>Extend your Workflows by using integrations for your favorite tools.</HeaderSubtitle>
          </>
        }
      />
      <div aria-label="My Integrations" className={styles.content} role="region" id="my-integrations">
        <section className={styles.sectionContainer}>{props.children}</section>
      </div>
    </div>
  );
}
