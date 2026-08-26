import Workflows, { action, loader } from "Features/Workflows/Workflows";
import { WorkspaceContainer } from "Features/App/App";

// ssr:true (react-router.config.ts) means `loader`/`action` run server-side in Node - see
// GlobalParameters.tsx / app/routes/globalParameters.tsx for the reference conversion this
// follows. WorkspaceContainer still supplies useWorkspaceContext() client-side (unchanged -
// out of scope for this conversion); the loader/action below read the `:workspace` route param
// directly instead, same as Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx.
export { loader, action };

export default function WorkflowsRoute() {
  return (
    <WorkspaceContainer>
      <Workflows />
    </WorkspaceContainer>
  );
}
