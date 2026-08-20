import Quotas from "Features/WorkspaceDetailed/Quotas/Quotas";

// Quotas tab of /:workspace/manage. The workspace's own quota values come from the parent layout
// route's loader.

export default function ManageWorkspaceQuotasRoute() {
  return <Quotas />;
}
