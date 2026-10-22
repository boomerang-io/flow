import Editor from "Features/WorkflowEditor";
import { editorLoader, editorAction } from "Features/WorkflowEditor/editorRoute";

// ssr:true (react-router.config.ts) means loader/action run server-side in Node - see
// app/routes/globalParameters.tsx for the fuller rationale comment. useWorkspaceContext() is
// supplied by the parent layout route (routes/workspaceLayout.tsx), whose loader resolves the
// `:workspace` record server-side.
//
// One loader/action pair now covers the whole editor: the workflow compose, the workspace's
// other workflows, the changelog, the available parameters, both task catalogues, Configure's
// GitHub installation, the Schedules tab's schedules/calendar, and (still, via
// Components/TokenSection/tokenRoute) the Configure > Tokens tab. Everything under
// Features/WorkflowEditor is rendered inside Editor.tsx's descendant <Routes>, so those
// components read this loader's data through useMatches() (Features/WorkflowEditor/
// editorRouteData.ts) rather than useLoaderData(), and write back with a plain useFetcher() -
// the same shape TaskTemplateOverview.tsx uses from WorkspaceTasks' descendant <Routes>.
export const loader = editorLoader;
export const action = editorAction;

export default function EditorRoute() {
  return <Editor />;
}
