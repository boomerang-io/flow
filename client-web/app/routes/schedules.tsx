import Schedules, { loader } from "Features/Schedules/Schedules";
import { scheduleAction as action } from "Features/Schedules/scheduleRoute";
import { WorkspaceContainer } from "Features/App/App";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now. No `Protected`
// wrapper: there's no dedicated schedules permission in RoutePermissions (see
// Features/App/AppRoutes.tsx) - this route was gated only by WorkspaceContainer before this
// conversion too.
//
// The action is the shared scheduleAction (Features/Schedules/scheduleRoute.ts): every write on
// this page - ScheduleCreator's create, ScheduleEditor's update, SchedulePanelList's
// delete/toggle - submits through a bare useFetcher(), which resolves to this route.
export { loader, action };

export default function SchedulesRoute() {
  return (
    <WorkspaceContainer>
      <Schedules />
    </WorkspaceContainer>
  );
}
