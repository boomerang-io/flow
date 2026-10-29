# 0003 — One monorepo and one product tag build every image, on Ubuntu's chiseled Java runtime with an AOT cache

**Status:** accepted · **Date:** 2026-10-29

## Context

Flow's three images and two libraries deploy together, a contract change touches the backend and the webapp at once, and
operators need to know which images go together. A `service-core` start spends most of its time loading and linking
classes, which every rollout and restart pays; Java 25 can record that work once into an AOT cache (JEP 514/515).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Release: separate repositories and per-service tags; or one repository and one product tag for every image | Different cadences; or the images deploy together | Operators assemble a set by hand; or a one-line fix cuts a full release |
| Base: `eclipse-temurin:25-jre-alpine`; `cgr.dev/chainguard/jre` free tier | A shell matters; any Java major will do | 56 findings in the userland; only `:latest` is free and tracks the newest feature release |
| Base: `ubuntu/jre:25-26.04_edge`, digest-pinned | Java 25 must stay Java 25 | 0 findings at a current digest, refreshed by hand; no shell |
| Start: cold, a CDS archive, or the cache written at container start | Pods start rarely; Java 24; a container that keeps its filesystem | Full load every start; metadata only; a pod never keeps it |
| Start: an AOT cache recorded by a train stage on the runtime JRE | Pods are recreated often | One application start per image build; about 120 MB more per image |

## Decision

One repository, one tag, the chiseled base and a trained cache. `client-web/` is a module here, run as its own
server-rendering server, the browser's only origin (`client-web/react-router.config.ts:12`). A tag `5.x.y`, `-beta.N` or
`-rc.N` triggers `.github/workflows/ci-release.yml:8-12`, which builds all three images, adds `:latest` only for a
stable tag and publishes the SDKs at the same version; `sbom.yml` scans the same tags. Both Java images run on Ubuntu's
chiseled Java 25 runtime pinned by digest (`service-core/Dockerfile:68`, `service-dispatcher/Dockerfile:46`). A `train`
stage on the runtime image starts the application to context refresh with no database or cluster and writes
`service.aot` (`service-core/Dockerfile:45-66`, `service-dispatcher/Dockerfile:34-44`); the runtime stage copies the
extracted application and its cache with their timestamps, and the command maps the cache and sets compact object
headers (`service-core/Dockerfile:89-90`). It took 40 to 60 % off every measured start (`performance.md`, "Start-up").

## Consequences

- One pull request changes a route and the screen that calls it; the tag alone says which images go together.
- No shell in the Java images: JVM options go in `JDK_JAVA_OPTIONS`, a certificate authority is a mounted truststore, and
  the compose healthcheck is a compiled probe. The train `RUN` flags and the `ENTRYPOINT` MUST agree; a heap-layout
  change makes the JVM skip the cache with one warning, never fail.
- Revisit per-image tags for an engine-only pull line, the base when a stable `25-26.04` channel ships, and the cache if
  the emulated arm64 train stage or the image size becomes the complaint.
