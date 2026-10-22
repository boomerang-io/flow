import React from "react";
import { Button, InlineLoading } from "@carbon/react";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { APP_ROOT } from "Config/appConfig";

/*
 * The error/loading surface for the server-side sign-in flow (oidc.server.ts). The routes that
 * render it (/auth/callback, /auth/signin) do the actual work in their loader/action on the SSR
 * server: on success the browser only ever sees a redirect, so this component renders solely when
 * something went wrong (the loader returned an error) or as the brief document shell in between.
 *
 * On ANY failure: a readable error with a way back. Deliberately NO automatic sign-in retry from
 * here - the callback re-triggering sign-in is exactly how redirect loops are built. The button
 * navigates to the app root, whose 401 page owns starting a fresh sign-in.
 */
export default function AuthCallback({ error }: { error?: string | null }) {
  if (error) {
    return (
      <Error403
        title="Sign-in didn't complete"
        message={
          <>
            <p role="alert">{error}</p>
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
