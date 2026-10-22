# syntax=docker/dockerfile:1
# Two stages. The build stage runs Maven, so the image builds from a clean checkout with the
# repository root as the context (`docker compose build service-core`, or
# `docker build -f service-core/Dockerfile .`); the root .dockerignore keeps the context to the
# Maven modules. The runtime stage is a JRE plus the jar.
#
# The build stage runs on the builder's own platform: a jar is the same for every target, and a
# Maven build under arm64 emulation is many times slower than a native one. Only the runtime
# stage is built per platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Every module's POM, because the parent POM lists all four and the reactor refuses to start
# without them; sources only for the module this image needs.
COPY pom.xml ./
COPY service-core/pom.xml service-core/
COPY dispatcher-sdk/pom.xml dispatcher-sdk/
COPY service-dispatcher/pom.xml service-dispatcher/
COPY flow-sdk/pom.xml flow-sdk/
COPY service-core/src service-core/src

# The cache mount keeps the local Maven repository between builds, so a rebuild downloads only
# what changed. Tests run in CI (ci-core.yml), not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress --projects service-core --also-make package -Dmaven.test.skip=true

# The container health probe (docker/health/Health.java): the runtime image has no shell, curl
# or wget, so the compose healthcheck runs this class with the image's own java.
COPY docker/health/Health.java docker/health/
RUN javac -d /build/health docker/health/Health.java

# Ubuntu's chiseled JRE: the Java 25 runtime, glibc and CA certificates, no shell, no package
# manager, no coreutils - so no OpenSSL, busybox or coreutils advisories either. Patched from
# Canonical's Ubuntu security stream. `25-26.04_edge` is the only Java 25 channel so far; the tag
# is rebuilt in place, so the digest (the manifest list, one per architecture) is what pins the
# build. Refresh it deliberately, with the SBOM review:
#   docker buildx imagetools inspect ubuntu/jre:25-26.04_edge
FROM ubuntu/jre:25-26.04_edge@sha256:db40b3d2cd6ca6c88caede7aa01126540ba9bb71fcee2626c464ba42f3c00385
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

# OpenShift runs the container as an arbitrary UID in group 0: group 0 owns the files and can
# read and execute them. Set at copy time, since the runtime image has no shell for a RUN.
COPY --from=build --chown=10001:0 --chmod=0664 /build/service-core/target/service-core.jar service.jar
COPY --from=build --chown=10001:0 --chmod=0664 /build/health/Health.class Health.class
USER 10001

EXPOSE 7700
# No shell, so no `java $JAVA_OPTS`: the launcher reads JDK_JAVA_OPTIONS from the environment
# instead, which is where a deployment puts heap and GC flags.
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "service.jar"]
