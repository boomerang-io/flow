# syntax=docker/dockerfile:1
# Two stages, the same shape as service-core/Dockerfile: Maven in a build stage on the builder's
# own platform, so the image builds from a clean checkout with the repository root as the context
# (`docker compose build service-dispatcher`, or `docker build -f service-dispatcher/Dockerfile .`),
# then a JRE plus the jar.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Every module's POM (the reactor needs them all); sources for the dispatcher and the SDK it is
# built on, which `--also-make` builds first.
COPY pom.xml ./
COPY service-core/pom.xml service-core/
COPY dispatcher-sdk/pom.xml dispatcher-sdk/
COPY service-dispatcher/pom.xml service-dispatcher/
COPY flow-sdk/pom.xml flow-sdk/
COPY dispatcher-sdk/src dispatcher-sdk/src
COPY service-dispatcher/src service-dispatcher/src

# The cache mount keeps the local Maven repository between builds. Tests run in CI
# (ci-dispatcher.yml), not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress --projects service-dispatcher --also-make package -Dmaven.test.skip=true

# The container health probe (docker/health/Health.java), as in service-core/Dockerfile.
COPY docker/health/Health.java docker/health/
RUN javac -d /build/health docker/health/Health.java

# Ubuntu's chiseled JRE, pinned by digest - see service-core/Dockerfile for why and how to refresh.
FROM ubuntu/jre:25-26.04_edge@sha256:db40b3d2cd6ca6c88caede7aa01126540ba9bb71fcee2626c464ba42f3c00385
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

# OpenShift runs the container as an arbitrary UID in group 0: group 0 owns the files and can
# read and execute them. Set at copy time, since the runtime image has no shell for a RUN.
COPY --from=build --chown=10001:0 --chmod=0664 /build/service-dispatcher/target/service-dispatcher.jar service.jar
COPY --from=build --chown=10001:0 --chmod=0664 /build/health/Health.class Health.class
USER 10001

EXPOSE 7702
# No shell, so no `java $JAVA_OPTS`: the launcher reads JDK_JAVA_OPTIONS from the environment
# instead. An internal CA is a mounted truststore named there (-Djavax.net.ssl.trustStore), not a
# keytool run at start.
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "service.jar"]
