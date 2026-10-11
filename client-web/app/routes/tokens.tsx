import Tokens, { action, loader } from "Features/GlobalTokens/GlobalTokens";
import { useFeature } from "flagged";
import { ProtectedRoute } from "Features/App/App";
import { Protected } from "Features/App/AppRoutes";
import { FeatureFlag } from "Config/appConfig";

// ssr:true means loader/action run server-side in Node - see app/routes/globalParameters.tsx
// for the fuller rationale comment this file follows.
export { loader, action };

export default function TokensRoute() {
  // With security off there is nothing for a token to do (FeatureService's "tokens" flag).
  if (!useFeature(FeatureFlag.TokensEnabled)) {
    return <ProtectedRoute allowed={false}>{null}</ProtectedRoute>;
  }
  return (
    <Protected permission="canReadTokens">
      <Tokens />
    </Protected>
  );
}
