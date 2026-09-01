import Activity, { loader } from "Features/Activity";
import { Protected } from "Features/App/AppRoutes";

// ssr:true means this file's loader runs server-side - see app/routes/globalParameters.tsx.
// useWorkspaceContext() is supplied by the parent layout route (routes/workspaceLayout.tsx),
// whose loader resolves the `:workspace` record server-side.
export { loader };

export default function ActivityRoute() {
  return (
    <Protected permission="activityEnabled">
      <Activity />
    </Protected>
  );
}
