import WorkspaceParameters from "Features/Parameters/WorkspaceParameters";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

export default function WorkspaceParametersRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="workspaceParametersEnabled">
        <WorkspaceParameters />
      </Protected>
    </WorkspaceContainer>
  );
}
