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
# without them; sources only for the modules this image needs. `--also-make` builds lib-common.
COPY pom.xml ./
COPY lib-common/pom.xml lib-common/
COPY service-core/pom.xml service-core/
COPY dispatcher-sdk/pom.xml dispatcher-sdk/
COPY service-dispatcher/pom.xml service-dispatcher/
COPY lib-common/src lib-common/src
COPY service-core/src service-core/src

# The cache mount keeps the local Maven repository between builds, so a rebuild downloads only
# what changed. Tests run in CI (ci-core.yml), not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress --projects service-core --also-make package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine
ENV JAVA_OPTS=""
ENV BMRG_HOME=/opt/boomerang

WORKDIR $BMRG_HOME
COPY --from=build /build/service-core/target/service-core.jar service.jar

# OpenShift requires a numeric, arbitrarily-assigned UID in group 0 rather than a named user;
# chgrp/chmod g=u makes that arbitrary UID as capable as the intended one.
RUN chmod -R u+x $BMRG_HOME \
    && chgrp -R 0 $BMRG_HOME \
    && chmod -R g=u $BMRG_HOME
USER 2000

EXPOSE 7700
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar ./service.jar" ]
