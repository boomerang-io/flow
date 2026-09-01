# 0006 — One product version and tag builds every image

**Status:** accepted · **Date:** 2026-07-22 (alias tags dropped 2026-08-15 and 2026-09-01)

## Context

Version 4 tagged each service on its own line, so operators had to know which engine, flow, loader and
web versions worked together. With flow and engine merged into one deployable (0001), engine mode became
configuration rather than a separate artefact, and a separate engine version line would have recreated
the compatibility matrix the merge removed.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-service tags plus a published compatibility manifest | Services genuinely release independently | Operators still assemble a set by hand; the manifest is one more thing to get wrong |
| B. One product tag builds the whole image set | The images are only ever deployed together | A one-line fix to one image still cuts a full product release |
| C. Option B plus `engine@` alias tags for embedders | A v5 embedder needs an engine-only pull line | Two lines to keep in step for a consumer that does not exist |

## Decision

Option B. A tag matching `5.x.y`, `5.x.y-beta.z` or `5.x.y-rc.z` triggers `.github/workflows/ci-release.yml:9-13`,
which builds and pushes `flow-service-core`, `flow-service-dispatcher`, `flow-service-loader` and
`flow-client-web` with that tag; `:latest` is added only for a stable tag (`ci-release.yml:73-80`).
`sbom.yml:11-14` scans on the same patterns. The alias tags of option C were dropped: v5 has never
shipped, so there is no v5 embedder to carry through a deprecation window, and v4 embedders keep pulling
the v4 image tags, which stay published. No independent engine version line exists.

## Consequences

- "Which versions go together" is answered by the tag alone; the compose stack and the Helm chart pin one value.
- Every release is a full product release; the `/release` skill cuts the tag.
- Reopen alias tags only if a v5 embedder appears that needs an engine-only pull line.
