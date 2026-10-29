import { APP_ROOT } from "Config/appConfig";

// The last workspace a person opened, so pages outside a workspace (Home, Admin, Profile) can
// point the side nav at it. A plain cookie, per browser: the workspace layout writes it and the
// bootstrap loader reads it server-side, so the first paint already has the links.
export const LAST_WORKSPACE_COOKIE = "flow_last_workspace";

const ONE_YEAR_SECONDS = 60 * 60 * 24 * 365;

/** Remember the workspace being viewed. Browser only. */
export function rememberWorkspace(name: string) {
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${LAST_WORKSPACE_COOKIE}=${encodeURIComponent(name)}; Path=${APP_ROOT}; Max-Age=${ONE_YEAR_SECONDS}; SameSite=Lax${secure}`;
}

/** The remembered workspace on a request, or null. */
export function lastWorkspaceFrom(request: Request): string | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const [name, ...value] = part.trim().split("=");
    if (name === LAST_WORKSPACE_COOKIE) {
      try {
        return decodeURIComponent(value.join("=")) || null;
      } catch {
        return null;
      }
    }
  }
  return null;
}
