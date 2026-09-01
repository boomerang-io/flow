import Workflows, { action, loader } from "Features/Workflows/Workflows";

// ssr:true (react-router.config.ts) means `loader`/`action` run server-side in Node - see
// GlobalParameters.tsx / app/routes/globalParameters.tsx for the reference conversion this
// follows. useWorkspaceContext() is supplied by the parent layout route
// (routes/workspaceLayout.tsx), whose loader resolves the `:workspace` record server-side; the
// loader/action below read the `:workspace` route param directly, same as
// Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx.
export { loader, action };

export default function WorkflowsRoute() {
  return <Workflows />;
}
