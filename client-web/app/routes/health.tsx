/*
 * Liveness/readiness probe: GET /apps/flow/health -> 200 {"status":"UP"}.
 *
 * A resource route (loader only, no component) outside the App layout, so a probe answers from
 * this process alone: no root bootstrap, no call to service-core. The path carries the
 * basename like every other route the server answers (react-router.config.ts), which is where
 * docker-compose.yml's healthcheck and a Kubernetes probe point.
 */
export function loader() {
  return Response.json({ status: "UP" });
}
