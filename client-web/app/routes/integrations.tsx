import Integrations, { action, loader } from "Features/Integrations/Integrations";
import { WorkspaceContainer } from "Features/App/App";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
export { loader, action };

export default function IntegrationsRoute() {
  return (
    <WorkspaceContainer>
      <Integrations />
    </WorkspaceContainer>
  );
}
