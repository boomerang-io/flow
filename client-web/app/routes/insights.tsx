import Insights, { loader } from "Features/Insights";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

// ssr:true means this file's loader runs server-side - see app/routes/globalParameters.tsx.
export { loader };

export default function InsightsRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="insightsEnabled">
        <Insights />
      </Protected>
    </WorkspaceContainer>
  );
}
