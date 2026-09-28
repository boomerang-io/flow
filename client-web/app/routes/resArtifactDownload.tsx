import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF STREAMING resource route (no default export):
 * GET /res/workspace/:workspace/workflowrun/:runId/artifacts/:name pipes
 * GET /api/v2/workspace/{workspace}/workflowrun/{runId}/artifacts/{name} from service-core to the
 * browser. Backs the run view's Artifacts tab "Download" button (a plain <a href>, never a
 * fetch - see Config/resourceRoutes.ts's artifactDownload builder) so the browser never sees an
 * /api URL and the file streams straight through, whatever its size.
 *
 * Same three load-bearing reasons as resTaskrunLog.tsx/resWorkflowExport.tsx for using native
 * `fetch` (undici) rather than serverFetch's axios instance: undici's Response.body IS a web
 * ReadableStream a loader Response can carry untouched; serverFetch's 5s timeout is wrong for a
 * large transfer; and `request.signal` propagates a browser disconnect straight to the upstream
 * socket. Content-type, content-disposition AND content-length pass through unchanged, and the
 * upstream status (including 404 - deleted/unknown artifact, and 410 - expired) is forwarded
 * as-is rather than folded into a generic error.
 */

// Same two inline rules as resTaskrunLog.tsx (see its toVersionedPath comment): absolute base
// from CORE_SERVICE_INTERNAL_ORIGIN, and the /api/ -> /api/v2/ rewrite skipped under VITEST.
function toVersionedPath(url: string): string {
  if (process.env.VITEST) {
    return url;
  }
  return url.startsWith("/api/") && !url.startsWith("/api/v2/") ? url.replace(/^\/api\//, "/api/v2/") : url;
}

export async function loader({
  request,
  params,
}: {
  request: Request;
  params: { workspace: string; runId: string; name: string };
}) {
  const origin = process.env.CORE_SERVICE_INTERNAL_ORIGIN ?? "";
  const cookie = request.headers.get("cookie");

  let upstream: Response;
  try {
    upstream = await fetch(
      `${origin}${toVersionedPath(
        serviceUrl.workspace.artifact.getRunArtifact({
          workspace: params.workspace,
          runId: params.runId,
          name: params.name,
        }),
      )}`,
      {
        headers: cookie ? { cookie } : undefined,
        signal: request.signal,
      },
    );
  } catch {
    return new Response("artifact unavailable", {
      status: 502,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }

  const headers: Record<string, string> = {
    "content-type": upstream.headers.get("content-type") ?? "application/octet-stream",
  };
  const disposition = upstream.headers.get("content-disposition");
  if (disposition) {
    headers["content-disposition"] = disposition;
  }
  const length = upstream.headers.get("content-length");
  if (length) {
    headers["content-length"] = length;
  }
  return new Response(upstream.body, { status: upstream.status, headers });
}
