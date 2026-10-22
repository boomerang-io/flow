import { useLoaderData } from "react-router";
import AuthCallback from "Features/Auth/AuthCallback";

/*
 * The OIDC redirect landing. The loader (Features/Auth/oidc.server.ts - a .server module, so the
 * route-module split keeps remix-auth out of the client bundle) completes the whole dance
 * server-side and redirects on success; this component only ever renders the failure surface.
 */
export { callbackLoader as loader } from "Features/Auth/oidc.server";

export default function AuthCallbackRoute() {
  const data = useLoaderData<{ error?: string }>();
  return <AuthCallback error={data?.error} />;
}
