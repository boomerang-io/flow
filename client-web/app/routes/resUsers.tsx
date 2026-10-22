import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import type { PaginatedUserResponse } from "Types";

export type ResUsersResult = { ok: true; users: PaginatedUserResponse } | { ok: false };

/*
 * BFF resource route (no default export): GET /res/users[?<user-query params>] -> 200
 * ({ ok: true, users } | { ok: false }).
 *
 * Backs AddMemberSearch's on-demand read of the user list, which fires only when the
 * add-members modal content mounts - it cannot ride the members route's loader without making
 * every navigation to /:workspace/manage pay for the full /user/query read the modal usually
 * never needs (same on-demand rationale as resTask.tsx / TaskUpdateModal). The request's own
 * query string is forwarded verbatim as the upstream user-query parameters. Failures are folded
 * into `{ ok: false }` with a 200 status on purpose: the modal renders its own inline <Error>
 * for this case, and a thrown loader Response would land in a route error boundary instead.
 */
export async function loader({ request }: { request: Request }) {
  const query = new URL(request.url).search.slice(1);
  const api = serverFetch(request);
  try {
    const response = await api.get<PaginatedUserResponse>(serviceUrl.getUsers({ query }));
    return Response.json({ ok: true, users: response.data } satisfies ResUsersResult);
  } catch {
    return Response.json({ ok: false } satisfies ResUsersResult);
  }
}
