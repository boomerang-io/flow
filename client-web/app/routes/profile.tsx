import UserProfile from "Features/UserProfile";
import { userTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";

// ssr:true means loader/action run server-side in Node - see app/routes/globalParameters.tsx
// for the fuller rationale comment.
//
// The loader/action live in Components/TokenSection/tokenRoute.ts rather than next to
// Features/UserProfile: the only data this route fetches is the personal access token list
// rendered by <TokenSection> (Settings.tsx), which is shared with the workflow editor and the
// admin tokens route, so all three point at the one loader/action pair. UserProfile itself still
// reads the user off AppContext and is untouched.
export const loader = userTokensLoader;
export const action = tokenAction;

export default function ProfileRoute() {
  return <UserProfile />;
}
