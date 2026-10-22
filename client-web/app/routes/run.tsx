import Execution, { action, loader } from "Features/WorkflowRun/WorkflowRun";
import { Protected } from "Features/App/AppRoutes";

// ssr:true (react-router.config.ts) means `loader`/`action` run server-side in Node - see
// app/routes/globalParameters.tsx for the reference conversion this follows. The run detail view
// is the polling one: the loader supplies initial data and the component drives live updates
// with useRevalidator on an interval (see WorkflowRun.tsx), replacing react-query's
// refetchInterval. useWorkspaceContext() is supplied by the parent layout route
// (routes/workspaceLayout.tsx), whose loader resolves the `:workspace` record server-side.
export { action, loader };

export default function RunRoute() {
  return (
    <Protected permission="activityEnabled">
      <Execution />
    </Protected>
  );
}
