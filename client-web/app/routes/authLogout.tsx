import AuthLogout from "Features/Auth/AuthLogout";

/*
 * The sign-out landing. The component fetcher-POSTs to this route's own action
 * (Features/Auth/session.server.ts), which revokes the Flow session server-side, relays the
 * clearing Set-Cookie onto its response, and returns the destination the browser must
 * hard-navigate to next (the proxy's own signOutUrl in proxy mode, otherwise the app root).
 */
export { logoutAction as action } from "Features/Auth/session.server";

export default function AuthLogoutRoute() {
  return <AuthLogout />;
}
