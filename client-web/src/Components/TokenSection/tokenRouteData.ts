import { useMatches } from "react-router-dom";
import type { Token } from "Types";

/*
 * Client-safe half of the token route contract: the shapes a token-rendering route's loader
 * must produce, plus the hook components use to read them back.
 *
 * Deliberately split from tokenRoute.ts, which imports Config/serverFetch (Node-only:
 * `process.env`, no browser cookie jar). TokenSection/PermissionSelector are ordinary
 * components imported by non-route modules (Features/UserProfile/Settings,
 * Features/WorkflowEditor/Configure), so they must not pull the server module into the browser
 * bundle - route-module splitting (v8_splitRouteModules) only strips loader/action code out of
 * files under app/routes/ and what they re-export, not out of arbitrary shared imports.
 */

export interface TokenCatalog {
  resources: string[];
  actions: string[];
  rolePresets: Record<string, string[]>;
}

export interface TokenSectionData {
  tokens: Token[];
  // Mirrors the previous getTokensQuery.isError behaviour: a failed fetch resolves with a flag
  // rather than throwing, so the surrounding page chrome still renders (see GlobalParameters.tsx).
  errorLoading: boolean;
  // null when the route's loader didn't ask for one (a `user`-type token surface never renders
  // PermissionSelector) or when the catalog fetch itself failed.
  catalog: TokenCatalog | null;
}

/*
 * Every route that renders <TokenSection> or <CreateToken> MUST return this key from its loader.
 * Wrapped in a named key rather than returned bare so a route can carry its own unrelated loader
 * data alongside it (the editor route, for example).
 */
export interface TokenSectionRouteData {
  tokenSection: TokenSectionData;
}

/*
 * Read the token loader data off the matched route.
 *
 * useMatches() rather than useLoaderData(): TokenSection is rendered inside the workflow
 * editor's *descendant* <Routes> (Editor.tsx -> Configure.tsx), where useLoaderData() resolves
 * against the descendant match - which has no loader and therefore no entry in the data
 * router's loaderData. useMatches() reads the data router's own state directly, so it returns
 * the same answer from both surfaces (/profile, where TokenSection is a plain descendant of the
 * route element, and /:workspace/editor/:workflow/*, where it is not) without having to thread
 * the data as props through Editor/Configure or UserProfile/Settings.
 *
 * Returns undefined when no matched route supplied the key - the component's own fallback then
 * decides what to render, rather than this throwing during an unrelated route's render.
 */
export function useTokenSectionData(): TokenSectionData | undefined {
  const matches = useMatches();
  for (let index = matches.length - 1; index >= 0; index--) {
    const data = matches[index]?.data as Partial<TokenSectionRouteData> | undefined;
    if (data && typeof data === "object" && data.tokenSection) {
      return data.tokenSection;
    }
  }
  return undefined;
}
