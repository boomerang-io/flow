import WorkspaceParameters, { action, loader } from "Features/Parameters/WorkspaceParameters/WorkspaceParameters";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

// ssr:true (react-router.config.ts) means `loader`/`action` run server-side in Node - see
// GlobalParameters.tsx / app/routes/globalParameters.tsx for the reference conversion this
// follows. The loader owns the parameters read the table renders; without it a fetcher settle had
// nothing to revalidate and every write left a stale table (see WorkspaceParameters.tsx).
export { action, loader };

export default function WorkspaceParametersRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="workspaceParametersEnabled">
        <WorkspaceParameters />
      </Protected>
    </WorkspaceContainer>
  );
}
