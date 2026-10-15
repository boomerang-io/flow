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
COPY lib-common/pom.xml lib-common/
COPY service-core/pom.xml service-core/
COPY dispatcher-sdk/pom.xml dispatcher-sdk/
COPY service-dispatcher/pom.xml service-dispatcher/
COPY dispatcher-sdk/src dispatcher-sdk/src
COPY service-dispatcher/src service-dispatcher/src

# The cache mount keeps the local Maven repository between builds. Tests run in CI
# (ci-dispatcher.yml), not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress --projects service-dispatcher --also-make package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine
ENV JAVA_OPTS=""
ENV BMRG_HOME=/opt/boomerang

WORKDIR $BMRG_HOME
COPY --from=build /build/service-dispatcher/target/service-dispatcher.jar service.jar

RUN cp $JAVA_HOME/lib/security/cacerts .

# OpenShift requires a numeric, arbitrarily-assigned UID in group 0 rather than a named user;
# chgrp/chmod g=u makes that arbitrary UID as capable as the intended one.
RUN chmod -R u+x $BMRG_HOME \
    && chgrp -R 0 $BMRG_HOME \
    && chmod -R g=u $BMRG_HOME
USER 2000

EXPOSE 7702
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar ./service.jar" ]
