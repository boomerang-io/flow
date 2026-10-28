# syntax=docker/dockerfile:1
# Three stages. The build stage runs Maven, so the image builds from a clean checkout with the
# repository root as the context (`docker compose build service-core`, or
# `docker build -f service-core/Dockerfile .`); the root .dockerignore keeps the context to the
# Maven modules. The train stage starts the application once on the runtime JRE to record the JDK
# AOT cache. The runtime stage is that JRE plus the extracted application and its cache.
#
# The build stage runs on the builder's own platform: a jar is the same for every target, and a
# Maven build under arm64 emulation is many times slower than a native one. The train and runtime
# stages are built per platform: an AOT cache is tied to the exact JVM build that records it.
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

# The AOT cache only works with the extracted form of the application (the jar's libraries beside
# it in lib/, on the class path), never with the nested-jar executable, so the image ships the
# extracted layout: service.jar plus lib/.
RUN java -Djarmode=tools -jar service-core/target/service-core.jar extract \
    --destination /build/application --application-filename service.jar

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
FROM ubuntu/jre:25-26.04_edge@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3 AS train
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME
COPY --from=build /build/application ./

# The training run (JEP 514/515): start the application on the runtime JRE until the context has
# refreshed, then exit and write service.aot - the loaded classes, linked, plus their initialised
# state - which the runtime stage reads so a start skips that work. It runs with no database:
# migrations are off, the encryption key is a placeholder (nothing is encrypted during a refresh)
# and the Mongo URI points nowhere with a short selection timeout, so the beans that open the
# connection give up fast. The OTLP exporters are excluded: BuildKit hands every build step
# OTEL_TRACES_EXPORTER=otlp and a traces endpoint on its own Unix socket (executor/oci/spec.go),
# which Spring Boot maps over application.properties and the OTLP exporter refuses, so the train
# run must not build one; a deployment without OTEL_* variables builds none either. The JVM flags
# that shape the cache (compact object headers) MUST match the ENTRYPOINT's, or the JVM ignores
# the cache at start.
RUN ["java", "-XX:AOTCacheOutput=service.aot", "-XX:+UseCompactObjectHeaders", \
     "-Dspring.context.exit=onRefresh", "-Dflamingock.enabled=false", \
     "-Dflow.encrypt.secret=aot-training", "-Dflow.encrypt.salt=aot-training", \
     "-Dspring.mongodb.uri=mongodb://localhost:1/aot?serverSelectionTimeoutMS=1000&connectTimeoutMS=1000", \
     "-Dspring.autoconfigure.exclude=org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration,org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration", \
     "-jar", "service.jar"]

FROM ubuntu/jre:25-26.04_edge@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

# OpenShift runs the container as an arbitrary UID in group 0: group 0 owns the files and can
# read them (the extraction wrote them world-readable, directories traversable). The application
# tree comes from the train stage, timestamps intact: the JVM checks each jar's size and
# modification time against the cache and silently drops the cache on any difference.
COPY --from=train --chown=10001:0 $BMRG_HOME ./
COPY --from=build --chown=10001:0 --chmod=0664 /build/health/Health.class Health.class
USER 10001

# The product tag the release built this image from; read as flow.version, it is the version the
# webapp's About shows.
ARG FLOW_VERSION=dev
ENV FLOW_VERSION=$FLOW_VERSION

EXPOSE 7700
# No shell, so no `java $JAVA_OPTS`: the launcher reads JDK_JAVA_OPTIONS from the environment
# instead, which is where a deployment puts heap and GC flags. Compact object headers (JEP 519)
# take 4 bytes off every object; the AOT cache is the one the train stage recorded.
ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "-XX:AOTCache=service.aot", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "service.jar"]
