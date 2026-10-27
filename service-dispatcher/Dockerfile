# syntax=docker/dockerfile:1
# Three stages, the same shape as service-core/Dockerfile: Maven in a build stage on the builder's
# own platform, so the image builds from a clean checkout with the repository root as the context
# (`docker compose build service-dispatcher`, or `docker build -f service-dispatcher/Dockerfile .`),
# a train stage that records the JDK AOT cache on the runtime JRE, then that JRE plus the
# extracted application and its cache.
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

# The extracted layout (service.jar plus lib/), which the AOT cache needs - see service-core/Dockerfile.
RUN java -Djarmode=tools -jar service-dispatcher/target/service-dispatcher.jar extract \
    --destination /build/application --application-filename service.jar

# The container health probe (docker/health/Health.java), as in service-core/Dockerfile.
COPY docker/health/Health.java docker/health/
RUN javac -d /build/health docker/health/Health.java

# Ubuntu's chiseled JRE, pinned by digest - see service-core/Dockerfile for why and how to refresh.
FROM ubuntu/jre:25-26.04_edge@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3 AS train
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME
COPY --from=build /build/application ./

# The training run, as in service-core/Dockerfile: a start to context refresh with no cluster and
# no engine. The Kubernetes client is built from a placeholder namespace and connects to nothing
# during a refresh. The cache-shaping flags MUST match the ENTRYPOINT's.
RUN ["java", "-XX:AOTCacheOutput=service.aot", "-XX:+UseCompactObjectHeaders", \
     "-Dspring.context.exit=onRefresh", "-Dkube.namespace=aot-training", \
     "-jar", "service.jar"]

FROM ubuntu/jre:25-26.04_edge@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

# OpenShift runs the container as an arbitrary UID in group 0: group 0 owns the files and can
# read them. The application tree comes from the train stage with its timestamps, which the JVM
# checks against the cache - see service-core/Dockerfile.
COPY --from=train --chown=10001:0 $BMRG_HOME ./
COPY --from=build --chown=10001:0 --chmod=0664 /build/health/Health.class Health.class
USER 10001

EXPOSE 7702
# No shell, so no `java $JAVA_OPTS`: the launcher reads JDK_JAVA_OPTIONS from the environment
# instead. An internal CA is a mounted truststore named there (-Djavax.net.ssl.trustStore), not a
# keytool run at start. Compact object headers and the recorded AOT cache, as in service-core.
ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "-XX:AOTCache=service.aot", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "service.jar"]
