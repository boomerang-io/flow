import Workflows, { loader } from "Features/WorkspaceDetailed/Workflows/Workflows";

// Workflows tab of /:workspace/manage. Read-only, so a loader and no action.
// ssr:true means the loader runs server-side - see app/routes/globalParameters.tsx.
export { loader };

export default function ManageWorkspaceWorkflowsRoute() {
  return <Workflows />;
}
