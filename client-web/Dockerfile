FROM node:18-alpine
ENV BMRG_HOME=/opt/boomerang/server

WORKDIR $BMRG_HOME
COPY server .
RUN npm install --production

# Create user, chown, and chmod. 
# OpenShift requires that a numeric user is used in the USER declaration instead of the user name
RUN chmod -R u+x $BMRG_HOME \
    && chgrp -R 0 $BMRG_HOME  \
    && chmod -R g=u $BMRG_HOME
USER 2000

EXPOSE 3000
ENTRYPOINT node scripts/rewriteAssetPaths && node_modules/.bin/boomerang-webapp-server serve
