import Execution from "Features/WorkflowRun";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

export default function RunRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="activityEnabled">
        <Execution />
      </Protected>
    </WorkspaceContainer>
  );
}
