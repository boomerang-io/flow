import { data } from "react-router-dom";

/*
 * C6: replaces the ad-hoc `{ ok: boolean, errorMessage?: {...} }` envelope route `action`s used
 * to hand-construct. Two shapes now do the job instead of one universal wrapper:
 *
 *  - Success: the action returns its payload directly (e.g. `{ intent: "delete", label }`).
 *    Nothing marks it as "ok" - the absence of an `error` key is what does that.
 *  - Expected failure (validation, a downstream 4xx/5xx the component shows inline or as a
 *    toast - every catch block in this codebase, none of which re-throw): `actionError(...)`
 *    below.
 *
 * `data(payload, { status })` (react-router-dom) is a *returned*, not thrown, value - `fetcher.data`
 * unwraps it to `payload` with the right type, and the non-2xx status only affects the real HTTP
 * response (SSR/document requests); it does NOT send the route to its ErrorBoundary the way
 * `throw data(...)` would. That is what preserves today's behaviour: on failure the component
 * stays mounted and shows a toast/inline message rather than unmounting to an error page.
 * `throw`-based ErrorBoundary handling is for genuinely unexpected failures, which none of the
 * actions migrated under C6 currently have.
 */
export type ActionError = { error: { title: string; message: string } };

export function isActionError(value: unknown): value is ActionError {
  return typeof value === "object" && value !== null && "error" in value;
}

export function actionError<T extends ActionError>(payload: T, status = 400) {
  return data(payload, { status });
}
