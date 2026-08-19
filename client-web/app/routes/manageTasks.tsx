import WorkspaceTasks from "Features/TaskManager/WorkspaceTasks";
import { WorkspaceContainer } from "Features/App/App";

export default function ManageTasksRoute() {
  return (
    <WorkspaceContainer>
      <WorkspaceTasks />
    </WorkspaceContainer>
  );
}
