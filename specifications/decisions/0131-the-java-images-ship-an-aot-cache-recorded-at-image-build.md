# 0131 — The Java images ship a JDK AOT cache recorded at image build

**Status:** accepted · **Date:** 2026-10-27

## Context

A `service-core` or dispatcher start spends most of its time loading and linking classes before Spring has anything
to wire, and every Kubernetes rollout, scale-up and crash restart pays it. Java 25 can record that work once into an
AOT cache (JEP 514/515) and reuse it; the question was where to record it, given that the runtime image has no
shell and a pod's filesystem does not outlive the pod.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. No cache; start cold | Pods start rarely | Nothing to build; every start pays the full load |
| B. A CDS archive (`-XX:ArchiveClassesAtExit`) | Java 24 and earlier | Class metadata only, no linking or initialised state; the older mechanism Boot now recommends against |
| C. An AOT cache recorded by a train stage on the runtime JRE, shipped in the image | Pods are recreated often; the cache must match the exact JVM build | One application start per image build per platform (273 s for core in the release build, arm64 under emulation); about 120 MB more per image; the training config must mirror production's class loading |
| D. The cache written at container start (`-XX:AOTCacheOutput` on the first run) | A container restarts in place and keeps its filesystem | A pod never does: each new pod would pay the recording and never read it; needs a writable image directory |

## Decision

C. A `train` stage on the runtime image, not the Maven image, starts the application to context refresh with no
database or cluster and writes `service.aot` (`service-core/Dockerfile:45-66`, `service-dispatcher/Dockerfile:34-44`);
the runtime stage copies the extracted application and its cache with their timestamps, which the JVM checks, and the
command maps the cache and sets compact object headers (`service-core/Dockerfile:84-85`). It took 40 to 60 % off every
measured start (`performance.md`, "Start-up"), and the recording cost lands on the build, where it is paid once.

## Consequences

- Every pod start reads the cache; a `JDK_JAVA_OPTIONS` that changes the heap layout makes the JVM skip it with one
  warning, so a deployment can never be broken by it.
- The jar ships extracted (`service.jar` plus `lib/`); the flags in the train `RUN` and the `ENTRYPOINT` MUST agree.
- The training run is pinned down by properties because BuildKit hands every build step `OTEL_TRACES_EXPORTER=otlp`
  and a traces endpoint on its own socket, which Spring Boot maps above `application.properties`.
- Revisit if the emulated arm64 train stage pushes a release build past what the team accepts, or if image size is
  the complaint: dropping the train stage and the `-XX:AOTCache` flag returns to A with nothing else to change.
