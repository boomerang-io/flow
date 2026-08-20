import Quotas, { action, loader } from "Features/WorkspaceDetailed/Quotas/Quotas";

// Quotas tab of /:workspace/manage. The workspace's own quota values come from the parent layout
// route's loader; this route's loader fetches the *default* quotas the "Restore defaults" modal
// lists (previously a useQuery inside that modal, so it only ran once the modal was opened).
// ssr:true means both run server-side - see app/routes/globalParameters.tsx.
export { action, loader };

export default function ManageWorkspaceQuotasRoute() {
  return <Quotas />;
}
