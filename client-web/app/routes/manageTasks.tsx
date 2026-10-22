import WorkspaceTasks, { action, loader } from "Features/TaskManager/WorkspaceTasks";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
// useWorkspaceContext() is supplied by the parent layout route (routes/workspaceLayout.tsx),
// whose loader resolves the `:workspace` record server-side.
export { loader, action };

export default function ManageTasksRoute() {
  return <WorkspaceTasks />;
}
