import { formatErrorMessage } from "@boomerang-io/utils";
import queryString from "query-string";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { HttpMethod, TokenType } from "Constants";
import type { Token, TokenScopeType } from "Types";
import { actionError, type ActionError } from "Utils/actionResult";
import type { TokenCatalog, TokenSectionData, TokenSectionRouteData } from "./tokenRouteData";

/*
 * Server half of the token route contract - loader/action bodies shared by the three surfaces
 * that read or write tokens:
 *
 *   /admin/tokens                     Features/GlobalTokens             globalTokensLoader
 *   /profile                          Features/UserProfile              userTokensLoader
 *   /:workspace/editor/:workflow/*    Features/WorkflowEditor           workflowTokensLoader
 *   /:workspace/manage/tokens         Features/WorkspaceDetailed        workspaceTokensLoader
 *
 * All three share one `tokenAction` (create + delete), so CreateToken/Form and TokenSection can
 * submit the same intents from any of them with a plain useFetcher() and no explicit action
 * path. See tokenRouteData.ts for the client-safe types/hook, and GlobalParameters.tsx for the
 * reference conversion this follows.
 *
 * Node-only (imports Config/serverFetch) - import this from app/routes/* and route modules
 * only, never from a component.
 */

interface LoadTokenSectionArgs {
  types: TokenScopeType;
  // Omitted entirely (query-string drops undefined) rather than sent empty when a surface has no
  // principal to scope by - an empty `principals` filter would widen the query, not narrow it.
  principals?: string;
  catalog?: { scope: "global" | "workspace"; principal?: string };
}

export async function loadTokenSection(
  request: Request,
  { types, principals, catalog }: LoadTokenSectionArgs,
): Promise<TokenSectionData> {
  const api = serverFetch(request);

  // Settled, not `all`: the permission catalog failing must not blank out the token list (or the
  // other way round) - each degrades on its own, which is what the previous two independent
  // useQuery calls did.
  const [tokensResult, catalogResult] = await Promise.allSettled([
    api.get(serviceUrl.getTokens({ query: queryString.stringify({ types, principals }) })),
    catalog
      ? api.get(
          serviceUrl.getTokenCatalog({
            query: queryString.stringify({ scope: catalog.scope, principal: catalog.principal }),
          }),
        )
      : Promise.resolve(null),
  ]);

  const tokens: Token[] =
    tokensResult.status === "fulfilled" ? (tokensResult.value.data?.content ?? []) : [];

  return {
    tokens,
    errorLoading: tokensResult.status === "rejected",
    catalog:
      catalogResult.status === "fulfilled" && catalogResult.value
        ? (catalogResult.value.data as TokenCatalog)
        : null,
  };
}

/* /admin/tokens - Features/GlobalTokens/GlobalTokens.tsx */
export async function globalTokensLoader({ request }: { request: Request }): Promise<TokenSectionRouteData> {
  return {
    tokenSection: await loadTokenSection(request, {
      types: TokenType.Global,
      catalog: { scope: "global", principal: "**" },
    }),
  };
}

/*
 * /profile - the user's own personal access tokens.
 *
 * The principal is the current user's id, which the browser reads off AppContext but a server
 * loader has no access to, so the profile is fetched first. A `user`-type token never renders
 * PermissionSelector (see CreateToken/Form/index.tsx), so no catalog is requested.
 */
export async function userTokensLoader({ request }: { request: Request }): Promise<TokenSectionRouteData> {
  let principals: string | undefined;
  try {
    const response = await serverFetch(request).get(serviceUrl.getUserProfile());
    principals = response.data?.id;
  } catch (error) {
    return { tokenSection: { tokens: [], errorLoading: true, catalog: null } };
  }
  return { tokenSection: await loadTokenSection(request, { types: TokenType.User, principals }) };
}

/*
 * /:workspace/editor/:workflow/* - the `key` tokens minted for one workflow's own use, shown on
 * the editor's Configure > Tokens tab. `:workflow` is the workflow *name* (see
 * appConfig.appLink.editorCanvas call sites, which pass `workflow.name`), which is exactly the
 * principal Configure.tsx used to pass as `props.workflow.name`.
 */
export async function workflowTokensLoader({
  params,
  request,
}: {
  params: { workflow?: string };
  request: Request;
}): Promise<TokenSectionRouteData> {
  const workflow = String(params.workflow ?? "");
  return {
    tokenSection: await loadTokenSection(request, {
      types: TokenType.Key,
      principals: workflow,
      catalog: { scope: "workspace", principal: workflow },
    }),
  };
}

/*
 * /:workspace/manage/tokens - the `key` tokens issued for a workspace itself, principal'd by
 * workspace name exactly as the tab's own useQuery used to be
 * (`{ types: TokenType.Key, principals: workspace?.name }`).
 */
export async function workspaceTokensLoader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<TokenSectionRouteData> {
  const workspace = String(params.workspace ?? "");
  return {
    tokenSection: await loadTokenSection(request, {
      types: TokenType.Key,
      principals: workspace,
      catalog: { scope: "workspace", principal: workspace },
    }),
  };
}

export type TokenActionResult =
  | { intent: "delete" }
  | ({ intent: "delete" } & ActionError)
  // The create response is the only place the token secret is ever returned; CreateToken/Form
  // hands it straight to the modal's Result step and it is never persisted client-side.
  | { intent: "create"; token: Token & { token?: string } }
  | ({ intent: "create" } & ActionError)
  | ({ intent: "unknown" } & ActionError);

export const TOKEN_INTENTS = ["create", "delete"] as const;

/*
 * Handles exactly two intents and rejects everything else.
 *
 * The rejection is load-bearing, not defensive tidiness: a route has one action, and /profile
 * composes this one with the profile's own (app/routes/profile.tsx dispatches on `intent`). An
 * earlier version of this function fell through to the delete branch for any unrecognised
 * intent, so a profile submission reaching it would have read a missing `tokenId` and fired
 * DELETE /token/undefined. Unknown intents now return an error result instead of deleting
 * anything - the consumers all narrow on `intent`, so an "unknown" result is inert for them.
 */
export async function tokenAction({ request }: { request: Request }) {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (!(TOKEN_INTENTS as readonly string[]).includes(intent)) {
    return actionError({
      intent: "unknown" as const,
      error: {
        title: "Unsupported Token Action",
        message: `The token action does not handle the "${intent}" intent.`,
      },
    });
  }

  if (intent === "create") {
    // JSON in a form field rather than encType:"application/json" - matches the GlobalParameters
    // conversion, and keeps the same fetcher able to carry the delete intent's plain fields.
    const body = JSON.parse(String(formData.get("body")));
    try {
      const response = await serverFetch(request)({
        url: serviceUrl.postToken(),
        data: body,
        method: HttpMethod.Post,
      });
      return { intent: "create" as const, token: response.data };
    } catch (error) {
      return actionError({
        intent: "create" as const,
        error: formatErrorMessage({ error, defaultMessage: "Create Token Failed" }),
      });
    }
  }

  // Reachable only for intent === "delete" now. Still checked explicitly: `String(null)` is the
  // string "undefined", which would otherwise be sent as a real path segment.
  const rawTokenId = formData.get("tokenId");
  if (!rawTokenId) {
    return actionError({
      intent: "delete" as const,
      error: { title: "Delete Token Failed", message: "No token was identified to delete." },
    });
  }

  const tokenId = String(rawTokenId);
  try {
    await serverFetch(request).delete(serviceUrl.deleteToken({ tokenId }));
    return { intent: "delete" as const };
  } catch (error) {
    return actionError({
      intent: "delete" as const,
      error: formatErrorMessage({ error, defaultMessage: "Delete Token Failed" }),
    });
  }
}
