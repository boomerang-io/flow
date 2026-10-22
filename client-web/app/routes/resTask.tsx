import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type { Task } from "Types";

export type ResTaskResult = { ok: true; task: Task } | { ok: false };

/*
 * BFF resource route (no default export): GET /res/task/:name[?version=N] -> 200
 * ({ ok: true, task } | { ok: false }).
 *
 * Backs TaskUpdateModal's on-demand read of the task at the version a canvas node is pinned to.
 * That read cannot ride the editor route's loader (the needed version is only known per-node,
 * when the modal opens), so it is a browser fetch of this route instead of a direct
 * GET /api/v2/task/{name}. Failures are folded into `{ ok: false }` with a 200 status on
 * purpose: the modal renders its own inline EmptyState for this case, and a thrown loader
 * Response would land in a route error boundary instead.
 */
export async function loader({ request, params }: { request: Request; params: { name: string } }) {
  const version = new URL(request.url).searchParams.get("version") ?? undefined;
  const api = serverFetch(request);
  try {
    const response = await api.get<Task>(serviceUrl.task.getTask({ name: params.name, version }));
    return Response.json({ ok: true, task: response.data } satisfies ResTaskResult);
  } catch {
    return Response.json({ ok: false } satisfies ResTaskResult);
  }
}
