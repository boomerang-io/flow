import axios from "axios";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";

export type ActivateActionResult =
  | { ok: true }
  | { ok: false; status: number | null; data: unknown };

/*
 * BFF resource route (action only): POST /res/activate with a JSON body { otc } -> PUT
 * /api/v2/activate upstream. Backs AppActivation's instance-activation submit (the
 * pre-first-admin bootstrap).
 *
 * This flow can run before any admin exists: whatever session cookie the browser holds at that
 * point is exactly what its previous direct PUT /api/activate carried, and serverFetch forwards
 * the inbound request's Cookie header verbatim - the one thing SystemControllerV2#register's
 * auth (AuthScope.session/user) reads. Upstream failure is returned as data (ok:false plus the
 * upstream status/body so AppActivation can keep feeding formatErrorMessage the same error
 * shape), never thrown - a thrown action would land in an error boundary instead of the modal's
 * inline notification.
 */
export async function action({ request }: { request: Request }) {
  const { otc } = ((await request.json()) ?? {}) as { otc?: string };
  const api = serverFetch(request);
  try {
    await api.put(serviceUrl.putActivationApp(), { otc });
    return { ok: true as const };
  } catch (error) {
    if (axios.isAxiosError(error)) {
      return { ok: false as const, status: error.response?.status ?? null, data: error.response?.data ?? null };
    }
    return { ok: false as const, status: null, data: null };
  }
}
