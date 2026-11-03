# syntax=docker/dockerfile:1
# Two build stages on node:24-alpine and a runtime stage on distroless Node 24 (.nvmrc pins CI to
# the same major; @react-router/* declare `engines.node >= 20`). `docker build client-web` produces
# a working image from a clean checkout:
# the build stage runs the frontend build itself, so nothing runs `pnpm run build` on the host
# first (neither docker-compose.yml nor .github/workflows/ci-release.yml does).
#
# The stages that run pnpm run on the builder's own platform: build/ is JavaScript and CSS and the
# serving packages are pure JavaScript, the same for every target, and a pnpm install under arm64
# emulation stalled for over 30 minutes in release builds. Only the runtime stage is per platform.

# ---------------------------------------------------------------------------------------------
# Build stage: pnpm install + react-router build. Nothing from here ships except build/.
# ---------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /src
# OpenShift runs an arbitrary UID in group 0; the runtime image has no shell, so what ships is made
# group-writable here, where it is built, and copied with its modes.

# Corepack activates the exact pnpm version pinned by package.json's `packageManager` field, so
# the image, CI (pnpm/action-setup reads the same field) and a developer's shell all resolve the
# lockfile with the same pnpm.
RUN corepack enable

# Dependencies are installed from just the manifest, lockfile, .npmrc (pnpm refuses a frozen
# install when its recorded settings differ) and patches/ (pnpm.patchedDependencies applies
# them at install time) first, so a source-only change doesn't reinstall.
COPY package.json pnpm-lock.yaml .npmrc ./
COPY patches ./patches
RUN pnpm install --frozen-lockfile

# The remaining context (see .dockerignore): app/, src/, public/, vite.config.mts,
# react-router.config.ts, tsconfig.json. Emits build/client (browser assets) + build/server (the
# server-rendering request handler - react-router.config.ts: ssr:true). The server bundle is
# self-contained (vite.config.mts: ssr.noExternal), so the runtime needs none of these packages.
COPY . .
RUN pnpm run build \
    && chgrp -R 0 /src/build \
    && chmod -R g=u /src/build

# ---------------------------------------------------------------------------------------------
# Serving packages: package.json's `dependencies` are exactly what the running server loads -
# @react-router/serve (the framework's own Express-based server) and react-router. Everything
# Vite bundles is under `devDependencies` and stays out of this stage.
# ---------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-alpine AS serve-deps
WORKDIR /src
RUN corepack enable
# patches/ because pnpm hashes every patch the lockfile records before it installs anything, even
# when none of the patched packages is part of this install.
COPY package.json pnpm-lock.yaml .npmrc ./
COPY patches ./patches
RUN pnpm install --prod --frozen-lockfile --ignore-scripts \
    && chgrp -R 0 /src/package.json /src/node_modules \
    && chmod -R g=u /src/package.json /src/node_modules

# ---------------------------------------------------------------------------------------------
# Runtime stage: react-router-serve over build/ on Google's distroless Node 24 (Debian 13), pinned
# by digest: node and its libraries alone, no shell, no package manager, no npm. The nonroot
# variant runs as uid 65532 in group 0; OpenShift's arbitrary UID is in group 0 too, and the
# files were made group-writable in the stages that built them.
# ---------------------------------------------------------------------------------------------
FROM gcr.io/distroless/nodejs24-debian13:nonroot@sha256:b8cec4b2f2126ff7bd034ec9ac207ffb3272667dfdc48376e3b035d3270549ba
ENV NODE_ENV=production
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

COPY --from=serve-deps --chown=65532:0 /src/package.json ./package.json
COPY --from=serve-deps --chown=65532:0 /src/node_modules ./node_modules
COPY --from=build --chown=65532:0 /src/build ./build

# react-router-serve reads PORT (and HOST) from the environment; docker-compose.yml and a
# Kubernetes Deployment set PORT=3000. The probe is GET /apps/flow/health (app/routes/health.tsx);
# with no shell or wget in the image, a probe that runs inside the container uses node itself.
# The image's entrypoint is node, so the command is its arguments.
EXPOSE 3000
CMD ["node_modules/@react-router/serve/dist/cli.js", "build/server/index.js"]
