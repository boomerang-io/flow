import Activity, { loader } from "Features/Activity";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

// ssr:true means this file's loader runs server-side - see app/routes/globalParameters.tsx.
export { loader };

export default function ActivityRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="activityEnabled">
        <Activity />
      </Protected>
    </WorkspaceContainer>
  );
}
