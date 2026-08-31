/*
 * URL builders for the app's own BFF resource routes (app/routes/res*.tsx - loader/action-only
 * route modules, no UI). These exist so browser code that cannot go through a route
 * loader/action - Yup async validation, an on-demand modal read, LazyLog's streaming fetch -
 * still never calls `/api/*` directly: the browser calls these same-origin routes on the SSR
 * server, whose server-side code makes the service-core call (Config/serverFetch.ts).
 *
 * Two path spaces, deliberately separate:
 * - Router-space paths (no basename) are for `useFetcher().submit()/load()` - the router
 *   prepends its own `/apps/flow` basename (react-router.config.ts).
 * - Browser-space URLs (basename included, via APP_ROOT) are for raw `fetch()`/LazyLog, which
 *   bypass the router entirely and need the real document path.
 *
 * The MSW handler layer (ApiServer/msw/handlers.ts) registers its mock routes off these same
 * builders, the same drift-proofing servicesConfig.ts's serviceUrl gives the `/api` surface.
 */
import { APP_ROOT } from "Config/appConfig";

export const resourceRoute = {
  // Router-space (fetcher targets)
  activateAction: () => "/res/activate",
  // Browser-space (raw fetch / LazyLog)
  workspaceValidateName: ({ name }: { name: string }) =>
    `${APP_ROOT}/res/workspace/validate-name?name=${encodeURIComponent(name)}`,
  scheduleValidateCron: ({ workspace, cron }: { workspace: string; cron: string }) =>
    `${APP_ROOT}/res/workspace/${workspace}/schedule/validate-cron?cron=${encodeURIComponent(cron)}`,
  users: ({ query }: { query?: string } = {}) => `${APP_ROOT}/res/users${query ? "?" + query : ""}`,
  task: ({ name, version }: { name: string; version?: string | number }) =>
    `${APP_ROOT}/res/task/${name}${version !== undefined && version !== null ? `?version=${version}` : ""}`,
  taskrunLog: ({ id }: { id: string }) => `${APP_ROOT}/res/taskrun/${id}/log`,
  taskYaml: ({ name, version, workspace }: { name: string; version?: string | number; workspace?: string }) => {
    const params = new URLSearchParams();
    if (version !== undefined && version !== null) params.set("version", String(version));
    if (workspace) params.set("workspace", workspace);
    const query = params.toString();
    return `${APP_ROOT}/res/task/${name}/yaml${query ? "?" + query : ""}`;
  },
  workflowExport: ({ workspace, workflow }: { workspace: string; workflow: string }) =>
    `${APP_ROOT}/res/workspace/${workspace}/workflow/${workflow}/export`,
};

/*
 * The name-availability probe both workspace-name forms run inside Yup's async `test`. Yup needs
 * a plain awaitable promise per keystroke, which a fetcher cannot provide (fetcher.submit is
 * fire-and-forget), so this is a raw same-origin fetch of the resource route instead. Contract
 * (see app/routes/resWorkspaceValidateName.tsx): 200 `{ available: boolean }`; anything else -
 * network failure included - reads as unavailable, preserving the previous behaviour where a
 * thrown validate-name POST marked the name TAKEN.
 */
/*
 * The cron-expression probe ScheduleManagerForm runs inside Yup's async `test` for the
 * advancedCron schedule type. Same shape as checkWorkspaceNameAvailable below: Yup needs a plain
 * awaitable promise per validation pass, so this is a raw same-origin fetch of the resource
 * route. Contract (see app/routes/resScheduleValidateCron.tsx): 200
 * `{ valid: boolean, message?: string }`; any transport failure folds into an invalid verdict
 * with a retry message so Yup's test never sees an unhandled rejection.
 */
export async function validateCronExpression({
  workspace,
  cron,
}: {
  workspace: string;
  cron: string;
}): Promise<{ valid: boolean; message?: string }> {
  try {
    const response = await fetch(resourceRoute.scheduleValidateCron({ workspace, cron }), {
      credentials: "same-origin",
    });
    if (!response.ok) {
      return { valid: false, message: "Unable to validate the cron expression. Please try again." };
    }
    return (await response.json()) as { valid: boolean; message?: string };
  } catch {
    return { valid: false, message: "Unable to validate the cron expression. Please try again." };
  }
}

export async function checkWorkspaceNameAvailable(name: string): Promise<boolean> {
  try {
    const response = await fetch(resourceRoute.workspaceValidateName({ name }), { credentials: "same-origin" });
    if (!response.ok) {
      return false;
    }
    const body = (await response.json()) as { available?: boolean };
    return body.available === true;
  } catch {
    return false;
  }
}
