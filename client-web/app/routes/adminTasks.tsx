import AdminTasks, { action, loader } from "Features/TaskManager/AdminTasks";
import { Protected } from "Features/App/AppRoutes";

// ssr:true means this file's loader/action run server-side - see app/routes/globalParameters.tsx.
export { loader, action };

export default function AdminTasksRoute() {
  return (
    <Protected permission="canReadTasks">
      <AdminTasks />
    </Protected>
  );
}
