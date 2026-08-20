import ApproverGroups from "Features/WorkspaceDetailed/ApproverGroups/ApproverGroups";

// Approver Groups tab of /:workspace/manage. No loader: the groups live on the workspace record
// the parent layout route loads.

export default function ManageWorkspaceApproverGroupsRoute() {
  return <ApproverGroups />;
}
