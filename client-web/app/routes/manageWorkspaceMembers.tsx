import Members, { action } from "Features/WorkspaceDetailed/Members/Members";

// Index route of /:workspace/manage (the Members tab keeps the bare path it has always had).
// No loader: the member list is part of the workspace record the parent layout route
// (app/routes/manageWorkspace.tsx) already loads, and adding/removing a member re-runs that
// parent loader because a fetcher submission revalidates every matched route.
// ssr:true means the action runs server-side - see app/routes/globalParameters.tsx.
export { action };

export default function ManageWorkspaceMembersRoute() {
  return <Members />;
}
