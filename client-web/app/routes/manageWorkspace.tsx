import ManageWorkspace, { loader, shouldRevalidate } from "Features/WorkspaceDetailed/WorkspaceDetailed";

// Layout route for the Manage Workspace tabs - the nested children are declared in app/routes.ts
// and each owns its own loader/action; this one fetches the workspace record they all share.
// ssr:true means this loader runs server-side - see app/routes/globalParameters.tsx.
//
// The app-wide workspace *context* the shared components under these tabs read comes from the
// parent layout route (routes/workspaceLayout.tsx), whose loader resolves the `:workspace`
// record server-side and revalidates when the param changes - the rename flow's refresh path.
export { loader, shouldRevalidate };

export default function ManageWorkspaceRoute() {
  return <ManageWorkspace />;
}
