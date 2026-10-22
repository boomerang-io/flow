import React from "react";
import { InlineLoading } from "@carbon/react";
import { useFetcher } from "react-router-dom";
import { APP_ROOT } from "Config/appConfig";
import { browserNavigation } from "./authClient";

/*
 * The sign-out landing (/auth/logout - registered OUTSIDE the App layout in app/routes.ts). The
 * UIShell's built-in Sign Out affordance is a plain link (config.platform.signOutUrl - see
 * Navbar.tsx), so it cannot POST; it links here instead, and this component fetcher-POSTs to
 * this route's own action (session.server.ts), which makes the POST the link cannot: revoke the
 * session server-side, relay the clearing Set-Cookie onto the action response so the httpOnly
 * cookie leaves the browser, and return where to go next. The component then hard-navigates:
 * to the proxy's own sign-out URL when one is configured (proxy mode - otherwise the surviving
 * proxy session signs the caller straight back in on the next 401), else to the app root so the
 * bootstrap re-runs without the session.
 */
export default function AuthLogout() {
  const fetcher = useFetcher<{ redirectTo: string | null }>();
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    started.current = true;
    // No body needed - the action reads the inbound cookie, not the submission.
    fetcher.submit(null, { method: "post" });
  }, [fetcher]);

  React.useEffect(() => {
    if (fetcher.data) {
      browserNavigation.replace(fetcher.data.redirectTo ?? `${APP_ROOT}/`);
    }
  }, [fetcher.data]);

  return (
    <div style={{ display: "flex", justifyContent: "center", margin: "5rem 0" }}>
      <InlineLoading description="Signing you out..." />
    </div>
  );
}
