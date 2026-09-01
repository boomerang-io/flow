import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF STREAMING resource route (no default export):
 * GET /res/task/:name/yaml[?version=N][&workspace=W] pipes the task definition as YAML
 * (Accept: application/x-yaml against GET /api/v2/task/{name}, or the workspace-scoped
 * GET /api/v2/workspace/{workspace}/task/{name} when ?workspace= is present) from service-core
 * to the browser. Backs TaskTemplateEditor's Download action - previously a direct browser
 * axios /api call.
 *
 * Native `fetch` (undici) rather than serverFetch for the reasons resTaskrunLog.tsx documents:
 * the body is handed to the returned Response untouched (no buffering), and `request.signal`
 * propagates a browser disconnect to the upstream socket. Content-type and content-disposition
 * pass through so a server-supplied filename survives (the client falls back to
 * `${name}.yaml`).
 */

// Same two inline rules as resTaskrunLog.tsx (see its toVersionedPath comment).
function toVersionedPath(url: string): string {
  if (process.env.VITEST) {
    return url;
  }
  return url.startsWith("/api/") && !url.startsWith("/api/v2/") ? url.replace(/^\/api\//, "/api/v2/") : url;
}

export async function loader({ request, params }: { request: Request; params: { name: string } }) {
  const origin = process.env.CORE_SERVICE_INTERNAL_ORIGIN ?? "";
  const cookie = request.headers.get("cookie");
  const searchParams = new URL(request.url).searchParams;
  const version = searchParams.get("version") ?? undefined;
  const workspace = searchParams.get("workspace");

  const upstreamPath = workspace
    ? serviceUrl.workspace.task.getTask({ workspace, name: params.name, version })
    : serviceUrl.task.getTask({ name: params.name, version });

  let upstream: Response;
  try {
    upstream = await fetch(`${origin}${toVersionedPath(upstreamPath)}`, {
      headers: { accept: "application/x-yaml", ...(cookie ? { cookie } : {}) },
      signal: request.signal,
    });
  } catch {
    return new Response("task yaml unavailable", {
      status: 502,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }

  const headers: Record<string, string> = {
    "content-type": upstream.headers.get("content-type") ?? "application/x-yaml",
  };
  const disposition = upstream.headers.get("content-disposition");
  if (disposition) {
    headers["content-disposition"] = disposition;
  }
  return new Response(upstream.body, { status: upstream.status, headers });
}
