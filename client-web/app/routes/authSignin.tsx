import { useLoaderData } from "react-router";
import AuthCallback from "Features/Auth/AuthCallback";

/*
 * The sign-in starter: the SignedOut page's <Form method="post"> lands on the action, which runs
 * the server-side OIDC flow's first leg (Features/Auth/oidc.server.ts) and redirects to the
 * identity provider. A GET only ever renders the readable failure surface (?error=...) or
 * bounces back to the app root.
 */
export { signinAction as action, signinLoader as loader } from "Features/Auth/oidc.server";

export default function AuthSigninRoute() {
  const data = useLoaderData<{ error?: string }>();
  return <AuthCallback error={data?.error} />;
}
