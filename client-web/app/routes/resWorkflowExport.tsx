import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF STREAMING resource route (no default export):
 * GET /res/workspace/:workspace/workflow/:workflow/export pipes
 * GET /api/v2/workspace/{workspace}/workflow/{workflow}/export from service-core to the browser.
 * Backs WorkflowCard's Export action - previously a direct browser axios /api call, the BFF
 * teardown's last workflow-surface violation. Exported workflows can be large, so the body must
 * flow through, never accumulate.
 *
 * Native `fetch` (undici) rather than serverFetch's axios instance for the same three
 * load-bearing reasons resTaskrunLog.tsx documents: undici's Response.body IS a web
 * ReadableStream a loader Response can carry untouched; serverFetch's 5s timeout is wrong for a
 * large transfer; and `request.signal` propagates a browser disconnect straight to the upstream
 * socket. Content-type AND content-disposition pass through so a server-supplied filename
 * survives to the browser (the client still falls back to `${workflow}.json` when upstream
 * sends none).
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
  params: { workspace: string; workflow: string };
}) {
  const origin = process.env.CORE_SERVICE_INTERNAL_ORIGIN ?? "";
  const cookie = request.headers.get("cookie");

  let upstream: Response;
  try {
    upstream = await fetch(
      `${origin}${toVersionedPath(
        serviceUrl.workspace.workflow.getExportWorkflow({ workspace: params.workspace, workflow: params.workflow }),
      )}`,
      {
        headers: cookie ? { cookie } : undefined,
        signal: request.signal,
      },
    );
  } catch {
    return new Response("export unavailable", {
      status: 502,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }

  const headers: Record<string, string> = {
    "content-type": upstream.headers.get("content-type") ?? "application/json",
  };
  const disposition = upstream.headers.get("content-disposition");
  if (disposition) {
    headers["content-disposition"] = disposition;
  }
  return new Response(upstream.body, { status: upstream.status, headers });
}
