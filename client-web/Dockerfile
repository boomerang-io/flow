# Two stages, both on node:22-alpine so build-time and run-time Node agree (.nvmrc pins CI to the
# same major). @react-router/node, react-router and @react-router/dev all declare
# `engines.node >= 20.0.0` (the SSR handler genuinely will not boot on 18); 22 is Maintenance LTS,
# supported into 2027, so this isn't trading one soon-to-be-EOL runtime for another.
#
# `docker build client-web` produces a working image from a clean checkout: the build stage runs
# the frontend build itself, so nothing has to run `pnpm run build` on the host first (neither
# docker-compose.yml nor .github/workflows/ci-release.yml does any more).

# ---------------------------------------------------------------------------------------------
# Build stage: pnpm install + react-router build. Nothing from here ships except build/.
# ---------------------------------------------------------------------------------------------
FROM node:22-alpine AS build
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
# SSR request handler - react-router.config.ts: ssr:true).
COPY . .
RUN pnpm run build

# ---------------------------------------------------------------------------------------------
# Runtime stage: the Express SSR server (server/) plus the build output from the stage above.
# ---------------------------------------------------------------------------------------------
FROM node:22-alpine
ENV BMRG_HOME=/opt/boomerang

WORKDIR $BMRG_HOME

# `server/` (this SSR Express server - see server/index.js) and `build/` (copied out of the build
# stage) sit as siblings under $BMRG_HOME, exactly mirroring their layout in the checkout
# (client-web/server, client-web/build). server/index.js resolves the build directory relative
# to its own location (`../build`) precisely so this layout - not an arbitrary WORKDIR choice -
# is what makes local dev (`cd server && node index.js`) and the container agree.
#
# The server's own (npm, not pnpm) dependencies are installed from just the manifest/lockfile
# first (a cheap, rarely-invalidated layer) before the rest of server/ is copied in, so an
# index.js-only change doesn't reinstall.
COPY server/package.json server/package-lock.json ./server/
RUN npm install --prefix server --omit=dev

COPY server ./server
COPY --from=build /src/build ./build

# OpenShift requires a numeric, arbitrarily-assigned UID in group 0 rather than a named user -
# chgrp/chmod g=u makes that arbitrary UID as capable as the intended one.
RUN chmod -R u+x $BMRG_HOME/server \
    && chgrp -R 0 $BMRG_HOME \
    && chmod -R g=u $BMRG_HOME
USER 2000

WORKDIR $BMRG_HOME/server
EXPOSE 3000
ENTRYPOINT ["node", "index.js"]
