import React from "react";
import { Button, InlineLoading } from "@carbon/react";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { useLocation } from "react-router-dom";
import { APP_ROOT } from "Config/appConfig";
import { browserNavigation, completeOidcCallback } from "./authClient";

/*
 * The OIDC redirect landing (/auth/callback - registered OUTSIDE the App layout in app/routes.ts,
 * so it renders without the bootstrap). The whole exchange is browser-side (see authClient):
 * useEffect never runs during SSR, so the server renders only the "Signing you in" shell and the
 * id_token never transits the SSR server.
 *
 * On success: hard-navigate (replace, so Back does not re-land on the one-shot callback URL) to
 * the return path stashed at sign-in - a full document load, so the root bootstrap re-runs with
 * the fresh session cookie.
 *
 * On ANY failure: a readable error with a way back. Deliberately NO automatic sign-in retry from
 * here - the callback re-triggering sign-in is exactly how redirect loops are built. The button
 * navigates to the app root, whose 401 page owns starting a fresh sign-in.
 */

type CallbackState = { name: "working" } | { name: "error"; message: string };

export default function AuthCallback() {
  const [state, setState] = React.useState<CallbackState>({ name: "working" });
  // The router's search string - identical to window.location.search here (the callback always
  // arrives via a full document load), but readable under a memory router in specs.
  const { search } = useLocation();
  // React 18 StrictMode double-invokes effects in dev; the stash is one-shot, so the second
  // invocation must not run the exchange again.
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    started.current = true;
    completeOidcCallback(search)
      .then(({ returnPath }) => {
        browserNavigation.replace(returnPath);
      })
      .catch((error: unknown) => {
        setState({
          name: "error",
          message: error instanceof Error ? error.message : "Sign-in could not be completed.",
        });
      });
    // The ref guard makes this one-shot regardless of deps: a search-string change after the
    // exchange has started must not run a second exchange.
  }, [search]);

  if (state.name === "error") {
    return (
      <Error403
        title="Sign-in didn't complete"
        message={
          <>
            <p role="alert">{state.message}</p>
            <Button kind="tertiary" size="md" href={`${APP_ROOT}/`}>
              Back to sign-in
            </Button>
          </>
        }
      />
    );
  }

  return (
    <div style={{ display: "flex", justifyContent: "center", margin: "5rem 0" }}>
      <InlineLoading description="Signing you in..." />
    </div>
  );
}
