import Workspaces, { action, loader } from "Features/Workspaces/Workspaces";
import { Protected } from "Features/App/AppRoutes";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now.
export { loader, action };

export default function WorkspaceListRoute() {
  return (
    <Protected permission="canReadWorkspaces">
      <Workspaces />
    </Protected>
  );
}
