import Schedules, { loader } from "Features/Schedules/Schedules";
import { WorkspaceContainer } from "Features/App/App";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now. No `Protected`
// wrapper: there's no dedicated schedules permission in RoutePermissions (see
// Features/App/AppRoutes.tsx) - this route was gated only by WorkspaceContainer before this
// conversion too.
export { loader };

export default function SchedulesRoute() {
  return (
    <WorkspaceContainer>
      <Schedules />
    </WorkspaceContainer>
  );
}
