import React from "react";
import { Button, InlineLoading } from "@carbon/react";
import { Login } from "@carbon/react/icons";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { Form } from "react-router-dom";
import { attemptProxyExchange, fetchAuthConfig } from "./authClient";

/*
 * The signed-out page App.tsx renders when the root bootstrap comes back 401. What it offers
 * depends on GET /auth/config, fetched browser-side on mount (this component SSR-renders the
 * bare 403 shell; the sign-in surface only ever appears after hydration):
 *
 *   none  - nothing extra: exactly the 403 page the app showed before this feature (dev stack).
 *   proxy - ONE silent empty-body POST to /auth/exchange (the proxy already asserted identity);
 *           success re-runs the bootstrap via onSignedIn, failure falls through to the plain
 *           signed-out page. The single-attempt guard is the loop protection: a still-401
 *           bootstrap re-render keeps this component mounted, so the ref holds.
 *   oidc  - a Sign in button that submits a plain document POST to the server-side sign-in
 *           action (see oidc.server.ts), which answers with the redirect to the identity
 *           provider. The browser no longer builds any part of the OIDC request itself.
 *
 * Every failure lands on readable text with a retry - never a blank page and never an automatic
 * redirect (only the user's click navigates away).
 */

interface SignedOutProps {
  // Re-runs the root bootstrap (App.tsx passes the root revalidator) after a successful silent
  // proxy exchange - the new session cookie makes the same loader calls succeed this time.
  onSignedIn: () => void;
}

type Phase =
  | { name: "resolving" }
  | { name: "none" }
  | { name: "proxy-attempting" }
  | { name: "signed-out" } // terminal: mode resolved, no silent path succeeded - show the page
  | { name: "oidc" }
  | { name: "config-error" };

export default function SignedOut({ onSignedIn }: SignedOutProps) {
  const [phase, setPhase] = React.useState<Phase>({ name: "resolving" });
  // One proxy attempt per mount, ever - even across a config refetch.
  const proxyAttempted = React.useRef(false);
  // Read through a ref so resolveConfig has a stable identity: onSignedIn is the root
  // revalidator's revalidate, whose identity is not documented stable across renders, and a
  // changing identity in the mount effect's deps would refetch the config on every re-render.
  const onSignedInRef = React.useRef(onSignedIn);
  onSignedInRef.current = onSignedIn;

  const resolveConfig = React.useCallback(() => {
    let cancelled = false;
    fetchAuthConfig()
      .then((config) => {
        if (cancelled) return;
        if (config.mode === "oidc") {
          setPhase({ name: "oidc" });
        } else if (config.mode === "proxy") {
          if (proxyAttempted.current) {
            setPhase({ name: "signed-out" });
            return;
          }
          proxyAttempted.current = true;
          setPhase({ name: "proxy-attempting" });
          attemptProxyExchange()
            .then(() => {
              if (cancelled) return;
              // The cookie is set; ask the bootstrap to run again. Render the plain signed-out
              // page meanwhile - on success this component unmounts before anyone reads it.
              setPhase({ name: "signed-out" });
              onSignedInRef.current();
            })
            .catch(() => {
              if (!cancelled) setPhase({ name: "signed-out" });
            });
        } else {
          setPhase({ name: "none" });
        }
      })
      .catch(() => {
        if (!cancelled) setPhase({ name: "config-error" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  React.useEffect(() => resolveConfig(), [resolveConfig]);

  if (phase.name === "proxy-attempting") {
    return (
      <Error403
        title="Signing you in"
        message={<InlineLoading description="Completing sign-in with your identity provider..." />}
      />
    );
  }

  if (phase.name === "config-error") {
    return (
      <Error403
        title="You're not signed in"
        message={
          <>
            <p>The sign-in configuration could not be loaded.</p>
            <Button
              kind="tertiary"
              size="md"
              onClick={() => {
                setPhase({ name: "resolving" });
                resolveConfig();
              }}
            >
              Retry
            </Button>
          </>
        }
      />
    );
  }

  if (phase.name === "oidc") {
    // A plain document POST (reloadDocument) to the sign-in action: the action's response is a
    // 302 to the identity provider carrying the transient flow cookies, which ordinary browser
    // navigation handles exactly right. The oidc phase only renders after hydration (the config
    // fetch runs in an effect), so window is available for the return path.
    return (
      <Error403
        title="You're not signed in"
        message={
          <>
            <p>Your session has expired or you're not signed in. Sign in again to continue.</p>
            <Form method="post" action="/auth/signin" reloadDocument>
              <input
                type="hidden"
                name="returnPath"
                value={window.location.pathname + window.location.search}
              />
              <Button type="submit" renderIcon={Login} size="md">
                Sign in
              </Button>
            </Form>
          </>
        }
      />
    );
  }

  // resolving / none / signed-out: exactly the page the app showed before this feature existed.
  return (
    <Error403
      title="You're not signed in"
      message="Your session has expired or you're not signed in. Sign in again to continue."
    />
  );
}
