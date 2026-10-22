import { useFeature } from "flagged";
import { ProtectedRoute } from "Features/App/App";
import Tokens from "Features/WorkspaceDetailed/Tokens/Tokens";
import { FeatureFlag } from "Config/appConfig";
import { workspaceTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";

// Tokens tab of /:workspace/manage.
//
// Loader/action are the pair shared by all four token surfaces (see
// Components/TokenSection/tokenRoute.ts) - the tab's own useQuery/useMutation are gone. The
// action is required here, not optional: CreateToken/Form submits its "create" intent with a
// bare useFetcher(), which resolves to this route.
export const loader = workspaceTokensLoader;
export const action = tokenAction;

export default function ManageWorkspaceTokensRoute() {
  // With security off there is nothing for a token to do (FeatureService's "tokens" flag).
  if (!useFeature(FeatureFlag.TokensEnabled)) {
    return <ProtectedRoute allowed={false}>{null}</ProtectedRoute>;
  }
  return <Tokens />;
}
