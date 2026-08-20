import Editor from "Features/WorkflowEditor";
import { WorkspaceContainer } from "Features/App/App";
import { workflowTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";

// The editor's own data (workflow, revisions, tasks, changelog) is still react-query inside
// Features/WorkflowEditor - only the Configure > Tokens tab is loader-driven so far. Its
// <TokenSection> sits inside the editor's descendant <Routes>, so it reads this loader's data
// through useMatches() rather than useLoaderData() (see
// Components/TokenSection/tokenRouteData.ts) and submits back to `action` with a plain
// useFetcher(), the same way TaskTemplateOverview.tsx does from WorkspaceTasks' descendant
// <Routes>.
export const loader = workflowTokensLoader;
export const action = tokenAction;

export default function EditorRoute() {
  return (
    <WorkspaceContainer>
      <Editor />
    </WorkspaceContainer>
  );
}
