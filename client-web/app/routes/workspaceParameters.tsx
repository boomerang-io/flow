import WorkspaceParameters, { action } from "Features/Parameters/WorkspaceParameters/WorkspaceParameters";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

// ssr:true (react-router.config.ts) means `action` runs server-side in Node - see
// GlobalParameters.tsx / app/routes/globalParameters.tsx for the reference conversion this
// follows. No `loader` here: this page's read comes from useWorkspaceContext()
// (WorkspaceContainer's own client-side query, unchanged/out of scope), only the writes moved.
export { action };

export default function WorkspaceParametersRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="workspaceParametersEnabled">
        <WorkspaceParameters />
      </Protected>
    </WorkspaceContainer>
  );
}
