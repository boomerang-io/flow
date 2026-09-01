# 0005 — Fold the web client into the monorepo

**Status:** accepted · **Date:** 2026-07-22

## Context

The web app lived in its own repository (`flow.client.web`) on a 3.12.x version line while the backend
moved to version 5, so every API change needed a cross-repository pull request and a compatibility note.
The merged backend also changed enough paths (workspace rename, run model fields) that the client had to
be re-baselined anyway.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Stay in a separate repository | Frontend and backend release on different cadences | Permanent compatibility matrix; two places to change one contract |
| B. Move immediately, alongside the engine rebuild | Spare capacity exists | Runs beside the riskiest backend work and adds to it |
| C. Move after the merged backend image ships, keeping history | The backend contract has settled | A one-time history import and re-baseline |

## Decision

Option C. `client-web/` is a module of this repository with its history imported from the v4 line
(`client-web/package.json`). It builds with pnpm, is tested and built by its own path-filtered workflow
(`.github/workflows/ci-web.yml`), and ships as `boomerangio/flow-client-web` from the same product tag
as the backend (`.github/workflows/ci-release.yml:256-336`). It runs as its own Node server with server
rendering on (`client-web/react-router.config.ts:16-17`, `client-web/Dockerfile`); the browser talks only
to that server, which calls `service-core` through `CORE_SERVICE_INTERNAL_ORIGIN`
(`client-web/src/Config/serverFetch.ts:24`). It is deployed only with `standalone` mode; engine mode has no
user interface.

## Consequences

- One pull request changes an API and the screen that calls it; the end-to-end suite in `e2e/` proves both.
- The frontend joins the single 5.x version line (0006) and its old 3.12.x line ends.
- `service-core` does not serve static assets and needs no cross-origin headers; the Node server is the single origin.
- Revisit only if the web app needs a release cadence the backend cannot follow.
