import Schedules, { loader } from "Features/Schedules/Schedules";
import { scheduleAction as action } from "Features/Schedules/scheduleRoute";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now. No `Protected`
// wrapper: there's no dedicated schedules permission in RoutePermissions (see
// Features/App/AppRoutes.tsx). useWorkspaceContext() is supplied by the parent layout route
// (routes/workspaceLayout.tsx), whose loader resolves the `:workspace` record server-side.
//
// The action is the shared scheduleAction (Features/Schedules/scheduleRoute.ts): every write on
// this page - ScheduleCreator's create, ScheduleEditor's update, SchedulePanelList's
// delete/toggle - submits through a bare useFetcher(), which resolves to this route.
export { loader, action };

export default function SchedulesRoute() {
  return <Schedules />;
}
