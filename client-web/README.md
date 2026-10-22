# Boomerang Flow Web Client

The web client for Boomerang Flow, a low-code, cloud-native workflow automation tool.

Learn more about our project on the [docs site](https://useboomerang.io/docs). Open work is tracked in the
[issues](https://github.com/boomerang-io/flow/issues) of this repository.

## Local Development

The webapp is React Router 7 in framework mode with server rendering on, served by the framework's own
server (`react-router-serve`): the single origin the browser talks to, calling `service-core` server-side.
Against the repository's compose stack with `service-core` on the host (the root README, "Developing"):

```bash
pnpm install
pnpm dev                        # http://localhost:3000/apps/flow with hot reload; service-core at :7700
pnpm run build && pnpm start    # the built server, exactly what the image runs
```

The dev server reaches `service-core` at http://localhost:7700 unless `CORE_SERVICE_INTERNAL_ORIGIN` is
set; the built server takes `PORT`, `CORE_SERVICE_INTERNAL_ORIGIN` and the `window._SERVER_DATA` values
(`app/serverData.server.ts`) from the environment, as `docker-compose.yml` shows. The probe is
`GET /apps/flow/health`. Unit tests: `pnpm test` (vitest + MSW, run in UTC); `pnpm run typecheck`.
