import ManageWorkspace from "Features/WorkspaceDetailed";
import { WorkspaceContainer } from "Features/App/App";

export default function ManageWorkspaceRoute() {
  return (
    <WorkspaceContainer>
      <ManageWorkspace />
    </WorkspaceContainer>
  );
}
