import Integrations, { action, loader } from "Features/Integrations/Integrations";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
// useWorkspaceContext() is supplied by the parent layout route (routes/workspaceLayout.tsx),
// whose loader resolves the `:workspace` record server-side.
export { loader, action };

export default function IntegrationsRoute() {
  return <Integrations />;
}
