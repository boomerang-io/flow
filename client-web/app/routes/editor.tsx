import Editor from "Features/WorkflowEditor";
import { WorkspaceContainer } from "Features/App/App";

export default function EditorRoute() {
  return (
    <WorkspaceContainer>
      <Editor />
    </WorkspaceContainer>
  );
}
