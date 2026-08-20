import ApproverGroups, { action } from "Features/WorkspaceDetailed/ApproverGroups/ApproverGroups";

// Approver Groups tab of /:workspace/manage. No loader: the groups live on the workspace record
// the parent layout route loads. Delete and create/update both post to this route's single
// intent-keyed action, whose completion revalidates that parent loader.
// ssr:true means the action runs server-side - see app/routes/globalParameters.tsx.
export { action };

export default function ManageWorkspaceApproverGroupsRoute() {
  return <ApproverGroups />;
}
