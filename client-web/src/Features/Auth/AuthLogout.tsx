import React from "react";
import { InlineLoading } from "@carbon/react";
import { APP_ROOT } from "Config/appConfig";
import { browserNavigation, logout } from "./authClient";

/*
 * The sign-out landing (/auth/logout - registered OUTSIDE the App layout in app/routes.ts). The
 * UIShell's built-in Sign Out affordance is a plain link (config.platform.signOutUrl - see
 * Navbar.tsx), so it cannot POST; it links here instead, and this component makes the POST the
 * link cannot: revoke the session server-side + clear the cookie (both done by the response's
 * Set-Cookie), then hard-navigate to the app root so the bootstrap re-runs without the session.
 * Browser-side on purpose - the Set-Cookie clearing the httpOnly session must reach the browser,
 * which a server-side loader call would swallow.
 */
export default function AuthLogout() {
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    started.current = true;
    logout().then(() => {
      browserNavigation.replace(`${APP_ROOT}/`);
    });
  }, []);

  return (
    <div style={{ display: "flex", justifyContent: "center", margin: "5rem 0" }}>
      <InlineLoading description="Signing you out..." />
    </div>
  );
}
