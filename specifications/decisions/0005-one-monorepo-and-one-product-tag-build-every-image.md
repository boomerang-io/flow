# 0005 — One monorepo and one product tag build every image, and the Java images run on Ubuntu's chiseled runtime

**Status:** accepted · **Date:** 2026-10-15

## Context

Flow's three images (core, dispatcher, webapp) and one library deploy together, a contract change touches the backend
and the webapp at once, and operators need to know which images go together without a compatibility table. The
vulnerability scan (`.github/workflows/sbom.yml`) reports every advisory in a base image's userland.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Release: A. Separate repositories, per-service tags and a compatibility manifest | Components release on different cadences | Operators assemble a set by hand; two pull requests per contract change |
| Release: B. One repository; one product tag builds every image | The images deploy together | A one-line fix to one image cuts a full release |
| Base: C. `eclipse-temurin:25-jre-alpine` | A shell in the container matters | 56 findings (13 High) in busybox, coreutils, OpenSSL and zlib; none in Java |
| Base: D. `cgr.dev/chainguard/jre`, free tier | Any Java major will do | Only `:latest` is free, so the Java major cannot be pinned |
| Base: E. `ubuntu/jre:25-26.04_edge`, digest-pinned | Java 25 must stay Java 25 | 13 findings (2 Critical, 9 High, 2 Medium in the 5.0.0-rc.1 scan), all in the Go runtime of `/usr/bin/pebble`, which the entrypoint never runs; no shell |

## Decision

B and E. `client-web/` is a module here with its history, tested by `.github/workflows/ci-web.yml` and run as its own
server-rendering Node server (`client-web/react-router.config.ts:12`), the browser's only origin, which calls
`service-core` through `CORE_SERVICE_INTERNAL_ORIGIN` (`client-web/src/Config/serverFetch.ts:35`). A tag `5.x.y`,
`5.x.y-beta.N` or `5.x.y-rc.N` triggers `.github/workflows/ci-release.yml:8-12`, which builds all three images from the
checkout under that tag, adds `:latest` only for a stable tag (`:58-73`) and publishes `dispatcher-sdk` at the same
version (`:96-123`); `sbom.yml:10-14` scans on the same tags. Both Java images run on Ubuntu's chiseled Java 25 runtime
pinned by digest (`service-core/Dockerfile:37`, `service-dispatcher/Dockerfile:28`), the only base that pins the Java
major and drops the userland the service never calls.

## Consequences

- One pull request changes a route and the screen that calls it, and `e2e/` proves both. The tag alone says which images
  go together; every release is a full product release, cut with the `/release` skill.
- No shell in the Java images: JVM options go in `JDK_JAVA_OPTIONS`, OpenShift's group-0 permissions are set at copy
  time (`service-core/Dockerfile:43-44`), an internal certificate authority is a mounted truststore named there, and the
  compose healthcheck runs a compiled probe with the image's own `java` (`docker/health/Health.java`).
- Revisit per-image tags if an embedder needs an engine-only pull line, and the base when Canonical publishes a stable
  `25-26.04` channel or a customer's scanner weighs the `pebble` findings.
