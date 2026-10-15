# syntax=docker/dockerfile:1
# Three stages on node:24-alpine (.nvmrc pins CI to the same major; @react-router/* declare
# `engines.node >= 20`). `docker build client-web` produces a working image from a clean checkout:
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
RUN pnpm run build

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
RUN pnpm install --prod --frozen-lockfile --ignore-scripts

# ---------------------------------------------------------------------------------------------
# Runtime stage: react-router-serve over build/, nothing else.
# ---------------------------------------------------------------------------------------------
FROM node:24-alpine
ENV NODE_ENV=production
ENV BMRG_HOME=/opt/boomerang
WORKDIR $BMRG_HOME

COPY --from=serve-deps /src/package.json ./package.json
COPY --from=serve-deps /src/node_modules ./node_modules
COPY --from=build /src/build ./build

# OpenShift requires a numeric, arbitrarily-assigned UID in group 0 rather than a named user -
# chgrp/chmod g=u makes that arbitrary UID as capable as the intended one.
RUN chgrp -R 0 $BMRG_HOME \
    && chmod -R g=u $BMRG_HOME
USER 2000

# react-router-serve reads PORT (and HOST) from the environment; docker-compose.yml and a
# Kubernetes Deployment set PORT=3000. The probe is GET /apps/flow/health (app/routes/health.tsx).
EXPOSE 3000
ENTRYPOINT ["node", "node_modules/@react-router/serve/dist/cli.js", "build/server/index.js"]
