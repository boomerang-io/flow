import AdminTasks from "Features/TaskManager/AdminTasks";
import { Protected } from "Features/App/AppRoutes";

export default function AdminTasksRoute() {
  return (
    <Protected permission="canReadTasks">
      <AdminTasks />
    </Protected>
  );
}
