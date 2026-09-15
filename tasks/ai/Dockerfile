# flow-task-ai - the worker image behind the `ai` task type.
#
# An `ai` task author never builds a container: the dispatcher resolves this image and the
# `prompt` command for every TaskRun of type `ai`
# (service-dispatcher/src/main/java/io/boomerang/executor/TaskImageResolver.java). It is shipped
# as one of the product images on the product tag, so `boomerangio/flow-task-ai:<version>` always
# matches the service-core and service-dispatcher of the same release.
#
# wolfi-base: Chainguard's hardened, glibc, near-zero-CVE base. The free tier publishes `:latest`
# only - there is no digest to pin that stays pullable - and the image is rebuilt on every product
# tag anyway, so the floating tag is the intended usage rather than a shortcut.
FROM cgr.dev/chainguard/wolfi-base:latest

# bash for the command, curl for the HTTP call, jq to build the request and read the response.
# grep/sed/coreutils are named explicitly rather than left to busybox, so the command behaves the
# same in the image as it does under tasks/ai/test/run.sh; ca-certificates-bundle so an HTTPS
# endpoint verifies.
# `apk upgrade` first: the base tag can lag the package repository, and installing a fresh jq onto
# an older glibc leaves `libjq.so.1` unable to resolve its symbols (caught by building the image).
RUN apk upgrade --no-cache \
    && apk add --no-cache bash curl jq coreutils grep sed ca-certificates-bundle

COPY commands/ /opt/boomerang/commands/
# The dispatcher sets the container command to the bare name `prompt`, so it has to be on PATH.
# wolfi-base ships no /usr/local/bin, hence /usr/bin.
RUN chmod +x /opt/boomerang/commands/*.sh \
    && ln -s /opt/boomerang/commands/prompt.sh /usr/bin/prompt

# Non-root. 2000 matches the numeric user the Flow service images run as; OpenShift requires a
# numeric USER rather than a name.
RUN addgroup -g 2000 flow \
    && adduser -D -u 2000 -G flow flow
USER 2000

# No ENTRYPOINT: the dispatcher sets the container command (`prompt`), and leaving the entrypoint
# empty keeps a future second command (`embed`, `classify`) a one-line change.
CMD ["prompt"]
