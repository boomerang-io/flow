import ManageWorkspace, { loader } from "Features/WorkspaceDetailed/WorkspaceDetailed";
import { WorkspaceContainer } from "Features/App/App";

// Layout route for the Manage Workspace tabs - the nested children are declared in app/routes.ts
// and each owns its own loader/action; this one fetches the workspace record they all share.
// ssr:true means this loader runs server-side - see app/routes/globalParameters.tsx.
//
// WorkspaceContainer stays (as it did before the nested-route split): it supplies the app-wide
// workspace *context* that shared components under these tabs read, and its react-query entry is
// still the one the rename flow re-fetches when the `:workspace` param changes.
export { loader };

export default function ManageWorkspaceRoute() {
  return (
    <WorkspaceContainer>
      <ManageWorkspace />
    </WorkspaceContainer>
  );
}
