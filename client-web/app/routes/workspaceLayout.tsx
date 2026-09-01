import { Error404 } from "@boomerang-io/carbon-addons-boomerang-react";
import axios from "axios";
import { Outlet, useLoaderData, useOutletContext } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import type { RoutePermissions } from "Features/App/AppRoutes";
import { WorkspaceIntent } from "Features/WorkspaceDetailed/WorkspaceDetailed";
import { WorkspaceContextProvider } from "State/context";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { FlowWorkspace } from "Types";

// Layout route wrapping the 12 workspace-scoped routes (see app/routes.ts): resolves the
// `:workspace` path param to its workspace record ONCE, server-side, and feeds the app-wide
// WorkspaceContextProvider every workspace-scoped screen reads via useWorkspaceContext().
//
// This replaces App.tsx's WorkspaceContainer in the route tree (BFF wave 2): that component
// fetched the workspace with react-query in the BROWSER - a direct /api call the BFF direction
// forbids - and returned null while loading or on error, so SSR emitted no content for any
// workspace-scoped route and a failed fetch was a permanently blank content area. ssr:true means
// this loader runs in Node via serverFetch (cookie forwarded - see Config/serverFetch.ts);
// loaders block render, so the loading state disappears, and both failure modes below render a
// real page instead of null.
//
// The provider's shape is unchanged ({ workspace }), so none of the consumer components are
// rewired. The specs' rtlContextRouterRender harness still supplies WorkspaceContextProvider
// directly (setupTests.tsx) and is unaffected.

type LoaderData =
  | { status: "ok"; workspace: FlowWorkspace }
  | { status: "notFound" }
  | { status: "error" };

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get<FlowWorkspace>(
      serviceUrl.resourceWorkspace({ workspace: String(params.workspace) }),
    );
    return { status: "ok", workspace: response.data };
  } catch (error) {
    // An unknown slug is a not-found, same as the /:workspace/* catch-all renders for an unknown
    // sub-path. The real backend reports it as 400 TEAM_INVALID_REF (WorkspaceService.get -
    // BoomerangError puts it on BAD_REQUEST, not 404); the MSW handler uses 404. Treat both as
    // "this workspace does not exist"; anything else (5xx, unreachable core) is a real error.
    if (axios.isAxiosError(error) && (error.response?.status === 404 || error.response?.status === 400)) {
      return { status: "notFound" };
    }
    return { status: "error" };
  }
}

/*
 * A fetcher submission under this layout revalidates this loader by default - correct, workspace
 * mutations (parameters, members, quotas) should refresh the shared record. Two intents are the
 * exception, for exactly the reason Features/WorkspaceDetailed/WorkspaceDetailed.tsx's own
 * shouldRevalidate documents: a rename changes the `:workspace` slug and a delete removes the
 * record, so re-running this loader against the URL still in the address bar resolves notFound,
 * swaps the tree below for <Error404>, and unmounts the very component whose effect is about to
 * navigate away. Suppress those two (imported, not duplicated, so the values cannot drift) and
 * let everything else revalidate.
 */
const SUPPRESS_REVALIDATION_INTENTS: ReadonlyArray<string> = [WorkspaceIntent.Rename, WorkspaceIntent.Delete];

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

export default function WorkspaceLayoutRoute() {
  const data = useLoaderData() as LoaderData;
  // The App layout above hands RoutePermissions down through its <Outlet context> (see
  // AppFeatures in Features/App/App.tsx); forward it so Protected/useRoutePermissions() in the
  // child routes keep resolving exactly as they did when they sat directly under that outlet.
  const routePermissions = useOutletContext<RoutePermissions>();

  if (data.status === "notFound") {
    return <Error404 theme="boomerang" />;
  }

  if (data.status === "error") {
    return <ErrorDragon style={{ margin: "5rem 0" }} />;
  }

  return (
    <WorkspaceContextProvider value={{ workspace: data.workspace }}>
      <Outlet context={routePermissions} />
    </WorkspaceContextProvider>
  );
}
