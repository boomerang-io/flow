# node:18-alpine is EOL (April 2025). @react-router/node, react-router and @react-router/dev all
# declare `engines.node >= 20.0.0` (the SSR handler genuinely will not boot on 18), so this bump
# is required by the new server, not a drive-by upgrade. node:22-alpine (Maintenance LTS,
# supported into 2027) is picked over the bare minimum node:20-alpine (already EOL by the time
# of this change - see the release note in the PR description) so this isn't just trading one
# soon-to-be-EOL runtime for another.
FROM node:22-alpine
ENV BMRG_HOME=/opt/boomerang

WORKDIR $BMRG_HOME

# `server/` (this SSR Express server - see server/index.js) and `build/` (the frontend build -
# build/client browser assets + build/server SSR handler, produced by `pnpm run build` BEFORE
# this image is built - see package.json, .github/workflows/ci-release.yml's build-webapp job,
# and docker-compose.yml) are copied in as siblings under $BMRG_HOME, exactly mirroring their
# layout in the checkout (client-web/server, client-web/build). server/index.js resolves the
# build directory relative to its own location (`../build`) precisely so this layout - not an
# arbitrary WORKDIR choice - is what makes local dev (`cd server && node index.js`) and the
# container agree.
#
# Dependencies are installed from just the manifest/lockfile first (a cheap, rarely-invalidated
# layer) before the rest of server/ is copied in, so an index.js-only change doesn't reinstall.
COPY server/package.json server/package-lock.json ./server/
RUN npm install --prefix server --omit=dev

COPY server ./server
COPY build ./build

# OpenShift requires a numeric, arbitrarily-assigned UID in group 0 rather than a named user -
# chgrp/chmod g=u makes that arbitrary UID as capable as the intended one.
RUN chmod -R u+x $BMRG_HOME/server \
    && chgrp -R 0 $BMRG_HOME \
    && chmod -R g=u $BMRG_HOME
USER 2000

WORKDIR $BMRG_HOME/server
EXPOSE 3000
ENTRYPOINT ["node", "index.js"]
