import Workspaces from "Features/Workspaces";
import { Protected } from "Features/App/AppRoutes";

export default function WorkspaceListRoute() {
  return (
    <Protected permission="canReadWorkspaces">
      <Workspaces />
    </Protected>
  );
}
