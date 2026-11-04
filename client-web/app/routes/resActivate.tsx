import axios from "axios";
import { data } from "react-router";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { clearPendingActivation, readPendingActivation } from "Features/Auth/pendingActivation";

export type ActivateActionResult =
  | { ok: true }
  | { ok: false; status: number | null; data: unknown };

/*
 * BFF resource route (action only): POST /res/activate with a JSON body { otc } (the field name is the API's) -> the
 * activation-code step of the first admin. Backs AppActivation's instance-activation submit (the
 * pre-first-admin bootstrap).
 *
 * Two ways in, by how the person signed in. Behind a proxy it is PUT /api/v2/activate: whatever
 * proxy headers or session cookie the browser holds is exactly what the PUT carries, and
 * serverFetch forwards the inbound request's Cookie header verbatim. With the webapp's own
 * OpenID Connect sign-in there is no identity yet, only the verified sign-in the callback held in
 * a cookie (pendingActivation.ts): it goes to POST /internal/v1/auth/exchange with the code, and
 * the session cookie that mints is relayed onto this response. Upstream failure is returned as
 * data (ok:false plus the upstream status/body so AppActivation can keep feeding
 * formatErrorMessage the same error shape), never thrown - a thrown action would land in an error
 * boundary instead of the modal's inline notification.
 */
export async function action({ request }: { request: Request }) {
  const { otc } = ((await request.json()) ?? {}) as { otc?: string };
  const api = serverFetch(request);
  const pending = readPendingActivation(request);
  try {
    if (!pending) {
      await api.put(serviceUrl.putActivationApp(), { otc });
      return { ok: true as const };
    }
    const exchange = await api.post(serviceUrl.postAuthExchange(), { ...pending, otc });
    const headers = new Headers();
    for (const setCookie of exchange.headers["set-cookie"] ?? []) headers.append("Set-Cookie", setCookie);
    headers.append("Set-Cookie", clearPendingActivation());
    return data({ ok: true as const }, { headers });
  } catch (error) {
    if (axios.isAxiosError(error)) {
      return { ok: false as const, status: error.response?.status ?? null, data: error.response?.data ?? null };
    }
    return { ok: false as const, status: null, data: null };
  }
}
