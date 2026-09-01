import React from "react";
import { Button, InlineLoading } from "@carbon/react";
import { Login } from "@carbon/react/icons";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { Form, useFetcher, useHref, useLocation } from "react-router-dom";
import type { AuthConfig } from "./authClient";

/*
 * The signed-out page App.tsx renders when the root bootstrap comes back 401. What it offers
 * depends on GET /auth/config, which the ROOT LOADER fetched server-side in the same pass as the
 * 401 and handed down as the `config` prop - no browser fetch, no resolving phase, and the real
 * mode renders server-side (view-source on a 401 shows the actual sign-in surface): none - the
 * plain 403 page; proxy - ONE silent fetcher submission to the /auth/signin action
 * (intent=proxy-exchange, session.server.ts - the action forwards the proxy's identity headers
 * and relays the session Set-Cookie; the single-attempt ref is the loop protection, and it holds
 * because a still-401 bootstrap revalidation keeps this component mounted); oidc - a Sign in
 * button submitting a document POST to the server-side sign-in action (oidc.server.ts). Every
 * failure lands on readable text - never a blank page and never an automatic redirect (only the
 * user's click navigates away).
 */

interface SignedOutProps {
  // GET /auth/config as resolved by the root loader; null when it could not be loaded.
  config: AuthConfig | null;
  // Re-runs the root loader (which owns the config fetch) - the Retry affordance when it failed.
  onReloadConfig: () => void;
}

export default function SignedOut({ config, onReloadConfig }: SignedOutProps) {
  const fetcher = useFetcher<{ ok: boolean }>();
  // One proxy attempt per mount, ever - even across bootstrap revalidations.
  const proxyAttempted = React.useRef(false);
  // The return path derives from the router location (basename-aware via useHref, SSR-safe),
  // not window.location, which does not exist during the server render.
  const location = useLocation();
  const returnPath = useHref({ pathname: location.pathname, search: location.search });

  const mode = config?.mode ?? null;

  React.useEffect(() => {
    if (mode === "proxy" && !proxyAttempted.current) {
      proxyAttempted.current = true;
      fetcher.submit({ intent: "proxy-exchange" }, { method: "post", action: "/auth/signin" });
    }
  }, [mode, fetcher]);

  if (config === null) {
    return (
      <Error403
        title="You're not signed in"
        message={
          <>
            <p>The sign-in configuration could not be loaded.</p>
            <Button kind="tertiary" size="md" onClick={onReloadConfig}>
              Retry
            </Button>
          </>
        }
      />
    );
  }

  if (mode === "oidc") {
    // A plain document POST (reloadDocument) to the sign-in action: the action's response is a
    // 302 to the identity provider carrying the transient flow cookies, which ordinary browser
    // navigation handles exactly right.
    return (
      <Error403
        title="You're not signed in"
        message={
          <>
            <p>Your session has expired or you're not signed in. Sign in again to continue.</p>
            <Form method="post" action="/auth/signin" reloadDocument>
              <input type="hidden" name="returnPath" value={returnPath} />
              <Button type="submit" renderIcon={Login} size="md">
                Sign in
              </Button>
            </Form>
          </>
        }
      />
    );
  }

  // The silent exchange either hasn't failed yet (about to submit, in flight, or succeeded - on
  // success the relayed Set-Cookie is in the browser and the post-action revalidation re-runs
  // the bootstrap, which unmounts this page) - keep the working shell up rather than flashing
  // the terminal page. Only a failed attempt falls through.
  if (mode === "proxy" && !(fetcher.data && !fetcher.data.ok)) {
    return (
      <Error403
        title="Signing you in"
        message={<InlineLoading description="Completing sign-in with your identity provider..." />}
      />
    );
  }

  // none, or a failed proxy attempt: exactly the page the app showed before this feature existed.
  return (
    <Error403
      title="You're not signed in"
      message="Your session has expired or you're not signed in. Sign in again to continue."
    />
  );
}
