import { formatErrorMessage } from "@boomerang-io/utils";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { actionError, type ActionError } from "Utils/actionResult";

/*
 * Shared server half of the schedule writes - the action bodies that replaced the react-query
 * useMutation calls in Components/ScheduleCreator, Components/ScheduleEditor and
 * Components/SchedulePanelList (the last Wave 5 holdouts).
 *
 * Shared because those three components are rendered by TWO routes and submit through a bare
 * useFetcher(), which resolves to whichever route is matched: `/:workspace/schedules`
 * (app/routes/schedules.tsx, action exported there from this module) and the workflow editor's
 * Schedule tab (`/:workspace/editor/:workflow/*` - editorRoute.ts's editorAction dispatches the
 * SCHEDULE_INTENTS here, the same way it composes tokenRoute.ts's tokenAction).
 *
 * Node-only (imports Config/serverFetch) - import this from route loader/action modules only,
 * never from a component. Components declare their own narrowed copy of ScheduleActionResult
 * instead (see the ActionResult type in CreateWorkflow.tsx for the precedent).
 *
 * Intents are namespaced (`createSchedule`, not `create`) because the editor route's action
 * namespace already contains tokenRoute's bare "create"/"delete" and its own "createRevision".
 *
 * Field convention (as Features/Workflows/Workflows.tsx): "schedule" always carries a
 * JSON-stringified schedule payload; "id" always carries a bare identifier string.
 */

export const SCHEDULE_INTENTS = ["createSchedule", "updateSchedule", "toggleSchedule", "deleteSchedule"] as const;

export type ScheduleIntent = (typeof SCHEDULE_INTENTS)[number];

export type ScheduleActionResult =
  | { intent: ScheduleIntent }
  | ({ intent: ScheduleIntent } & ActionError)
  | ({ intent: "unknown" } & ActionError);

export async function scheduleAction({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}) {
  const workspace = String(params.workspace ?? "");
  const formData = await request.formData();
  const intent = String(formData.get("intent"));
  const api = serverFetch(request);

  if (intent === "deleteSchedule") {
    const id = String(formData.get("id"));
    try {
      await api.delete(serviceUrl.workspace.schedule.deleteSchedule({ workspace, id }));
      return { intent: "deleteSchedule" as const };
    } catch (error) {
      return actionError({
        intent: "deleteSchedule" as const,
        error: formatErrorMessage({ error, defaultMessage: "Delete Schedule Failed" }),
      });
    }
  }

  /*
   * Refusing the rest is load-bearing, not defensive tidiness - the same trap editorRoute.ts and
   * tokenRoute.ts document: an unrecognised intent falling into a write branch below would
   * `JSON.parse(String(null))` into `null` and fire a write with an empty body. Consumers narrow
   * on `intent`, so an "unknown" result is inert for them.
   */
  if (intent !== "createSchedule" && intent !== "updateSchedule" && intent !== "toggleSchedule") {
    return actionError({
      intent: "unknown" as const,
      error: {
        title: "Unsupported Schedule Action",
        message: `The schedule action does not handle the "${intent}" intent.`,
      },
    });
  }

  // JSON in a form field rather than encType:"application/json", matching the editorRoute and
  // Workflows conversions - one fetcher can carry every shape of payload.
  const body = JSON.parse(String(formData.get("schedule")));

  try {
    if (intent === "createSchedule") {
      await api.post(serviceUrl.workspace.schedule.postSchedule({ workspace }), body);
    } else {
      // updateSchedule and toggleSchedule are both a full-body PUT (toggle = the same schedule
      // with `status` flipped by the caller); they stay separate intents so each fetcher's
      // settle effect can tell its own result apart for toasts/modal handling.
      await api.put(serviceUrl.workspace.schedule.putSchedule({ workspace }), body);
    }
    return { intent };
  } catch (error) {
    return actionError({
      intent,
      error: formatErrorMessage({
        error,
        defaultMessage: intent === "createSchedule" ? "Create Schedule Failed" : "Update Schedule Failed",
      }),
    });
  }
}
