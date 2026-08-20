import WorkspaceTasks, { action, loader } from "Features/TaskManager/WorkspaceTasks";
import { WorkspaceContainer } from "Features/App/App";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
export { loader, action };

export default function ManageTasksRoute() {
  return (
    <WorkspaceContainer>
      <WorkspaceTasks />
    </WorkspaceContainer>
  );
}
