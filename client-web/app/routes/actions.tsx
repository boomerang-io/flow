import Actions, { action, loader } from "Features/Actions/Actions";
import { WorkspaceContainer } from "Features/App/App";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
export { loader, action };

export default function ActionsRoute() {
  return (
    <WorkspaceContainer>
      <Actions />
    </WorkspaceContainer>
  );
}
