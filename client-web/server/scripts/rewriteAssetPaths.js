/**
 * Replace all placeholder root context values with env variable
 */

const path = require("path");
const replace = require("replace-in-file");

const APP_ROOT = process.env.APP_ROOT || "";
const BUILD_DIR = "build";

const options = {
  files: path.join(__dirname, "..", BUILD_DIR, "**/*"),
  from: /\/BMRG_APP_ROOT_CONTEXT/g,
  to: APP_ROOT
};
const changes = replace.sync(options);
console.log("Rewrote the following files", changes);
