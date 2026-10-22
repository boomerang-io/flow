import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";

/*
 * BFF resource route (no default export, so react-router serves the loader's Response directly):
 * GET /res/workspace/:workspace/schedule/validate-cron?cron=<expr> -> 200
 * { valid: boolean, message?: string }.
 *
 * Backs the cron-expression probe inside ScheduleManagerForm's Yup async validation (see
 * Config/resourceRoutes.ts's validateCronExpression). The upstream contract is
 * GET /api/v2/workspace/{workspace}/schedule/validate-cron?cron=..., which answers 200
 * { valid, message? } - this loader relays exactly that pair and folds any upstream failure
 * (4xx/5xx, unreachable service-core) into `valid: false` with a retry message, because the
 * probe only ever asks "can this schedule be saved?" and a hard error must not wedge Yup's
 * async test with an unhandled rejection.
 */
export async function loader({ request, params }: { request: Request; params: { workspace: string } }) {
  const cron = new URL(request.url).searchParams.get("cron");
  if (!cron) {
    return Response.json({ valid: false, message: "Cron expression is required" }, { status: 400 });
  }
  const api = serverFetch(request);
  try {
    const response = await api.get<{ valid?: boolean; message?: string }>(
      serviceUrl.schedule.getCronValidation({ workspace: params.workspace, expression: encodeURIComponent(cron) }),
    );
    return Response.json({ valid: response.data.valid === true, ...(response.data.message ? { message: response.data.message } : {}) });
  } catch {
    return Response.json({ valid: false, message: "Unable to validate the cron expression. Please try again." });
  }
}
