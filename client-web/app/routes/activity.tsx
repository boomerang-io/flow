import Activity from "Features/Activity";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

export default function ActivityRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="activityEnabled">
        <Activity />
      </Protected>
    </WorkspaceContainer>
  );
}
