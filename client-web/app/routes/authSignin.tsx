import { useLoaderData } from "react-router";
import AuthCallback from "Features/Auth/AuthCallback";

/*
 * The sign-in starter, serving both sign-in submissions (Features/Auth/session.server.ts
 * dispatches on intent): the SignedOut page's Sign in <Form method="post"> starts the
 * server-side OIDC flow's first leg (Features/Auth/oidc.server.ts) and redirects to the
 * identity provider, while its silent proxy-exchange fetcher submission
 * (intent=proxy-exchange) runs the Java exchange server-side and relays the session
 * Set-Cookie. A GET only ever renders the readable failure surface (?error=...) or bounces
 * back to the app root.
 */
export { signinRouteAction as action } from "Features/Auth/session.server";
export { signinLoader as loader } from "Features/Auth/oidc.server";

export default function AuthSigninRoute() {
  const data = useLoaderData<{ error?: string }>();
  return <AuthCallback error={data?.error} />;
}
