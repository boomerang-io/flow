# IDPZero - a single-binary local OIDC provider (github.com/idpzero/idpzero) - the same tool
# ARCHIE uses for local full-stack auth (~/Workspaces/tlawrie/asdr: `.idpzero/server.yaml` +
# a host-run `idpzero serve`). ARCHIE runs it as a brew-installed host binary; this stack
# containerises it because the compose stack IS the local deployment here, and no official
# container image is published (releases are plain binaries), so this downloads the pinned
# release tarball for the build platform.
FROM alpine:3.20

ARG TARGETARCH
ARG IDPZERO_VERSION=0.3.0

# The release archives name the amd64 build "x86_64"; arm64 keeps its name.
RUN apk add --no-cache ca-certificates wget \
  && case "${TARGETARCH}" in \
       amd64) IDPZERO_ARCH="x86_64" ;; \
       arm64) IDPZERO_ARCH="arm64" ;; \
       *) echo "unsupported TARGETARCH '${TARGETARCH}'" >&2; exit 1 ;; \
     esac \
  && wget -q -O /tmp/idpzero.tar.gz \
       "https://github.com/idpzero/idpzero/releases/download/v${IDPZERO_VERSION}/idpzero_Linux_${IDPZERO_ARCH}.tar.gz" \
  && tar -xzf /tmp/idpzero.tar.gz -C /usr/local/bin idpzero \
  && rm /tmp/idpzero.tar.gz \
  && idpzero --version >/dev/null

# The config is baked into the image rather than volume-mounted on purpose: idpzero treats the
# config directory as writable state (it auto-creates cache/state.sqlite and signing keys next
# to server.yaml), and a bind mount would leak that runtime state back into the repo. Editing
# docker/idpzero/server.yaml therefore needs a `docker compose build idpzero` to take effect.
COPY server.yaml /idpzero/server.yaml

EXPOSE 4379

# /healthz comes from the underlying zitadel/oidc provider library.
ENTRYPOINT ["idpzero", "serve", "--config", "/idpzero"]
