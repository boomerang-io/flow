import Insights from "Features/Insights";
import { WorkspaceContainer } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";

export default function InsightsRoute() {
  return (
    <WorkspaceContainer>
      <Protected permission="insightsEnabled">
        <Insights />
      </Protected>
    </WorkspaceContainer>
  );
}
