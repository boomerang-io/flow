# 0108 — The Java images run on Ubuntu's chiseled JRE

**Status:** accepted · **Date:** 2026-10-15

## Context

The two Java images ran on `eclipse-temurin:25-jre-alpine`, a full Alpine userland: a shell, busybox,
coreutils and OpenSSL that the service never calls, and that the SBOM scan (`.github/workflows/sbom.yml`)
reports advisories against on every release. The scan on 2026-10-15 found 56 in that base, 13 of them High,
none of them in Java.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `eclipse-temurin:25-jre-alpine` | A shell in the container matters more than the scan | 56 findings (13 High) in busybox, coreutils, OpenSSL, zlib, libpng; 225 MB |
| B. `cgr.dev/chainguard/jre` (free tier) | Any Java major will do | 3 findings (Medium, alsa-lib) but only `:latest` is free, which is Java 27 today; 322 MB; no way to pin a major without a paid tag |
| C. `ubuntu/jre:25-26.04_edge`, digest-pinned | Java 25 must stay Java 25 | 9 findings (1 Critical, 7 High), all in `/usr/bin/pebble`, a Go service manager the image carries and the entrypoint never runs; 200 MB; `_edge` is the only Java 25 channel so far; no shell |

## Decision

Option C, as the reference codebases chose (ARCHIE's `services/service-core/Dockerfile`). It is the only
option that pins the Java major and removes the userland the service does not use
(`service-core/Dockerfile`, `service-dispatcher/Dockerfile`). No shell changes two things: `JAVA_OPTS` in a
`sh -c` entrypoint becomes `JDK_JAVA_OPTIONS`, which the launcher reads itself, and the OpenShift
group-0 permissions are set with `COPY --chown --chmod` rather than a `RUN chmod`.

## Consequences

- The scan moves from 56 findings to 9, and those 9 are in a binary the container never executes.
- An internal CA is a truststore mounted into the container and named in `JDK_JAVA_OPTIONS`
  (`-Djavax.net.ssl.trustStore=...`), not a `keytool` import at start: the v3 chart's command
  (`charts/bmrg-flow/templates/deployment-service.yaml:86`) relied on a shell and `keytool`, and neither
  exists in the image. The v5 chart must do it the mounted way.
- `kubectl exec` into a running container gets no shell; debugging is logs and an ephemeral debug container.
- A container healthcheck has no `wget` to call: the compose stack runs a compiled probe class with the
  image's own `java` (`docker/health/Health.java`, copied beside the jar). Kubernetes probes are HTTP from the
  kubelet and need nothing in the image.
- Revisit when Canonical publishes a `25-26.04` stable channel (drop `_edge`), or if `pebble` findings start to
  carry weight with a customer's scanner, in which case a `COPY --from` of the rootfs without it is the fix.
