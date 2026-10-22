import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF resource route (no default export, so react-router serves the loader's Response directly):
 * GET /res/workspace/validate-name?name=<kebab-name> -> 200 { available: boolean }.
 *
 * Backs the per-keystroke name-availability probe inside WorkspaceCreateContent's and
 * UpdateWorkspaceName's Yup async validation (see Config/resourceRoutes.ts's
 * checkWorkspaceNameAvailable). The upstream contract is POST /api/v2/workspace/validate-name,
 * which answers 200 for an available name and a 4xx for a collision - this loader folds that
 * into one stable JSON shape rather than relaying the status, because the probe only ever asks
 * a boolean question. Any upstream failure (collision, 5xx, unreachable service-core) reads as
 * `available: false`, preserving the previous browser-side behaviour where a thrown
 * validate-name POST marked the name TAKEN.
 */
export async function loader({ request }: { request: Request }) {
  const name = new URL(request.url).searchParams.get("name");
  if (!name) {
    return Response.json({ available: false }, { status: 400 });
  }
  const api = serverFetch(request);
  try {
    await api.post(serviceUrl.postWorkspaceValidateName(), { name });
    return Response.json({ available: true });
  } catch {
    return Response.json({ available: false });
  }
}
