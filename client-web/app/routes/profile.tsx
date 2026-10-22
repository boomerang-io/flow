import UserProfile, { action as profileAction } from "Features/UserProfile/UserProfile";
import { userTokensLoader, tokenAction } from "Components/TokenSection/tokenRoute";

// ssr:true means loader/action run server-side in Node - see app/routes/globalParameters.tsx
// for the fuller rationale comment.
//
// The loader lives in Components/TokenSection/tokenRoute.ts rather than next to
// Features/UserProfile: the only data this route fetches is the personal access token list
// rendered by <TokenSection> (Settings.tsx), which is shared with the workflow editor and the
// admin tokens route, so all three point at the one loader. UserProfile itself still reads the
// user off AppContext.
export const loader = userTokensLoader;

/*
 * A route has exactly one action, but this page has two independent groups of write sites that
 * both submit through a bare useFetcher() (which resolves to the nearest matched route - this
 * one): the token list's create/delete, and the profile's own updateProfile/deleteAccount.
 * They are dispatched by `intent` here rather than chained, because tokenAction treats every
 * intent it does not recognise as a token delete (it falls through to reading `tokenId`), so
 * handing it a profile intent would fire a DELETE against an undefined token id.
 *
 * The body of a Request can only be read once, so the intent is peeked off a clone and the
 * untouched original is passed to whichever sub-action owns it.
 */
const TOKEN_INTENTS = new Set(["create", "delete"]);

export async function action({ request }: { request: Request }) {
  const intent = String((await request.clone().formData()).get("intent"));
  return TOKEN_INTENTS.has(intent) ? tokenAction({ request }) : profileAction({ request });
}

export default function ProfileRoute() {
  return <UserProfile />;
}
