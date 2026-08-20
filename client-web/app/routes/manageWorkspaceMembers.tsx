import Members from "Features/WorkspaceDetailed/Members/Members";

// Index route of /:workspace/manage (the Members tab keeps the bare path it has always had).
// No loader: the member list is part of the workspace record the parent layout route
// (app/routes/manageWorkspace.tsx) already loads.

export default function ManageWorkspaceMembersRoute() {
  return <Members />;
}
