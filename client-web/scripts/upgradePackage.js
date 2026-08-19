"use strict";

/**
 * Makes the script crash on unhandled rejections instead of silently
 * ignoring them. In the future, promise rejections that are not handled will
 * terminate the Node.js process with a non-zero exit code.
 */
process.on("unhandledRejection", (err) => {
  throw err;
});

const chalk = require("chalk");
const fs = require("fs-extra");
const inquirer = require("inquirer");
const path = require("path");
const spawn = require("react-dev-utils/crossSpawn");
const compareVersions = require("./utils/compareVersions");
const sortObjectKeys = require("./utils/sortObjectKeys");

const fileNames = {
  env: ".env",
  package: "package.json",
  templatePackage: "template.json",
  docker: "Dockerfile",
  eslintrc: "eslintrc.js",
  jsconfig: "jsconfig.json",
  travis: ".travis.yml",
  yarnLock: "yarn.lock",
  craTemplate: "@boomerang/cra-template-boomerang",
};

const appPath = path.resolve(__dirname, "..");
const packagePath = path.join("..", fileNames.package);
const appPackage = require(packagePath);
const useYarn = fs.existsSync(path.join(appPath, fileNames.yarnLock));

const bcraTemplateName = fileNames.craTemplate;
let command;
let remove;
let args;

if (useYarn) {
  command = "yarnpkg";
  remove = "remove";
  args = ["add"];
} else {
  command = "npm";
  remove = "uninstall";
  args = ["install", "--save", bcraTemplateName].filter((e) => e);
}

let installProc = spawn.sync(command, args, { stdio: "inherit" });
if (installProc.status !== 0) {
  console.error(`\`${command} ${args.join(" ")}\` failed`);
  return;
}

const templatePath = path.join(require.resolve(bcraTemplateName, { paths: [appPath] }), "..");

const templateJsonPath = path.join(templatePath, fileNames.templatePackage);
let templateJson = {};
if (fs.existsSync(templateJsonPath)) {
  templateJson = require(templateJsonPath);
}

const jsconfigPath = path.join(appPath, fileNames.jsconfig);
let appJsconfig = {};
if (fs.existsSync(jsconfigPath)) {
  appJsconfig = require(jsconfigPath);
}

const templateJsconfigJsonPath = path.join(templatePath, "template", fileNames.jsconfig);
let templateJsconfig = {};
if (fs.existsSync(templateJsconfigJsonPath)) {
  templateJsconfig = require(templateJsconfigJsonPath);
}

const templatePackage = templateJson.package || {};

const askUpdateSpecificScripts = async (choices) => {
  const questions = [
    {
      name: "selectedScripts",
      type: "checkbox",
      message: `Select the scripts that you want to update. It will replace what you currently have.`,
      default: choices,
      choices: choices.map((script) => ({
        checked: true,
        value: script.name,
        name: `${script.name} ${script.new ? "(new)" : ""}`,
      })),
    },
  ];
  return await inquirer.prompt(questions);
};

const askUpdateSpecificDependencies = async (choices) => {
  const questions = [
    {
      name: "selectedDependencies",
      type: "checkbox",
      message: `Select the dependencies that you want to update.`,
      default: choices,
      choices: choices.map((dependency) => ({
        checked: true,
        value: dependency.name,
        name: `${dependency.name} (${
          dependency.version ? dependency.version : dependency.oldVersion + " -> " + dependency.newVersion
        })`,
      })),
    },
  ];
  return await inquirer.prompt(questions);
};

const askUpdateSpecificConfig = async (choices) => {
  const questions = [
    {
      name: "selectedConfig",
      type: "checkbox",
      message: `Select the tooling config that you want to update. It will replace what you currently have.`,
      default: choices,
      choices: choices.map((config) => ({
        checked: true,
        value: config.name,
        name: `${config.name} ${config.new ? "(new)" : ""}`,
        type: config.type,
      })),
    },
  ];
  return await inquirer.prompt(questions);
};

const askUpdateJsconfig = async (choices) => {
  const questions = [
    {
      name: "selectedJsconfig",
      type: "checkbox",
      message: `Select the jsconfig.json properties that you want to update. If you don't have the file in your project, one will be created.`,
      default: choices,
      choices: choices.map((config) => ({
        checked: true,
        value: config.name,
        name: `${config.name} ${config.new ? "(new)" : ""}`,
        type: config.type,
      })),
    },
  ];
  return await inquirer.prompt(questions);
};

const run = async () => {
  let changedScripts = [];
  let changedDependencies = [];
  let changedConfig = [];
  let changedJsconfig = [];
  let handleDevDependencies = false;

  // Check if app package.json and template have devDependencies
  if (Object.keys(templatePackage.devDependencies).length > 0) {
    handleDevDependencies = true;
    if (appPackage.devDependencies === undefined) {
      appPackage.devDependencies = {};
    }
  }
  /**
   * Update or add scripts scripts
   */
  let potentialScripts = [];
  Object.keys(templatePackage.scripts).forEach((script) => {
    if (!appPackage.scripts[script]) {
      potentialScripts.push({ name: script, new: true });
    } else if (templatePackage.scripts[script] !== appPackage.scripts[script]) {
      potentialScripts.push({ name: script, new: false });
    }
  });

  // Actually update things
  if (potentialScripts.length) {
    console.log();
    const { selectedScripts } = await askUpdateSpecificScripts(potentialScripts);
    potentialScripts
      .filter((script) => selectedScripts.includes(script.name))
      .forEach((script) => {
        appPackage.scripts[script.name] = templatePackage.scripts[script.name];
        changedScripts.push(script);
      });
  }
  /**
   * Update or add dependency
   */
  let potentialDependencies = [];
  Object.keys(templatePackage.dependencies).forEach((dependency) => {
    const templateVersion = templatePackage.dependencies[dependency];
    if (!appPackage.dependencies[dependency]) {
      potentialDependencies.push({
        name: dependency,
        version: templateVersion,
      });
    } else if (compareVersions(templateVersion, appPackage.dependencies[dependency]) === 1) {
      potentialDependencies.push({
        name: dependency,
        newVersion: templateVersion,
        oldVersion: appPackage.dependencies[dependency],
      });
    }
  });

  /**
   * Update or add dev dependency
   */
  Object.keys(templatePackage.devDependencies).forEach((dependency) => {
    const templateVersion = templatePackage.devDependencies[dependency];
    if (!appPackage.devDependencies[dependency]) {
      potentialDependencies.push({
        name: dependency,
        version: templateVersion,
        devDependency: true,
      });
    } else if (compareVersions(templateVersion, appPackage.devDependencies[dependency]) === 1) {
      potentialDependencies.push({
        name: dependency,
        newVersion: templateVersion,
        oldVersion: appPackage.devDependencies[dependency],
        devDependency: true,
      });
    }
  });

  // Actually update things
  if (potentialDependencies.length) {
    console.log();
    const { selectedDependencies } = await askUpdateSpecificDependencies(potentialDependencies);
    potentialDependencies
      .filter((dependency) => selectedDependencies.includes(dependency.name))
      .forEach((dependency) => {
        if (dependency.devDependency) {
          appPackage.devDependencies[dependency.name] = dependency.version || dependency.newVersion;
        } else {
          appPackage.dependencies[dependency.name] = dependency.version || dependency.newVersion;
        }
        changedDependencies.push(dependency);
      });
  }
  /**
   * Update tooling configuration
   */
  const {
    // eslint-disable-next-line no-unused-vars
    homepage,
    // eslint-disable-next-line no-unused-vars
    scripts,
    // eslint-disable-next-line no-unused-vars
    dependencies,
    // eslint-disable-next-line no-unused-vars
    devDependencies,
    ...templateConfig
  } = templatePackage;

  const appPackageConfig = {};
  for (let property in templateConfig) {
    appPackageConfig[property] = appPackage[property];
  }

  if (JSON.stringify(templateConfig) !== JSON.stringify(appPackageConfig)) {
    const potentialConfig = [];

    // Compare the template package.json config to the current project
    for (let property in templateConfig) {
      if (!appPackageConfig[property]) {
        potentialConfig.push({
          name: property,
          new: true,
        });
      } else if (JSON.stringify(templateConfig[property]) !== JSON.stringify(appPackageConfig[property])) {
        potentialConfig.push({
          name: property,
          new: false,
        });
      }
    }

    if (potentialConfig.length) {
      console.log();
      const { selectedConfig } = await askUpdateSpecificConfig(potentialConfig);
      selectedConfig.forEach((property) => {
        appPackage[property] = templatePackage[property];
        changedConfig.push(potentialConfig.find((config) => selectedConfig.includes(config.name)));
      });
    }
  }

  /**
   * Update jsconfig.json
   */
  if (JSON.stringify(templateJsconfig) !== JSON.stringify(appJsconfig)) {
    const potentialConfig = [];
    for (let property in templateJsconfig) {
      if (!appJsconfig[property]) {
        potentialConfig.push({
          name: property,
          new: true,
        });
      } else if (JSON.stringify(templateJsconfig[property]) !== JSON.stringify(appJsconfig[property])) {
        potentialConfig.push({
          name: property,
          new: false,
        });
      }
    }

    if (potentialConfig.length) {
      console.log();
      const { selectedJsconfig } = await askUpdateJsconfig(potentialConfig);
      selectedJsconfig.forEach((property) => {
        appJsconfig[property] = templateJsconfig[property];
        changedJsconfig.push(potentialConfig.find((config) => selectedJsconfig.includes(config.name)));
      });
    }
  }

  // Sort appPackage dependencies and scripts
  appPackage.scripts = sortObjectKeys(appPackage.scripts);
  appPackage.dependencies = sortObjectKeys(appPackage.dependencies);
  if (handleDevDependencies) {
    appPackage.devDependencies = sortObjectKeys(appPackage.devDependencies);
  }

  // Overwrite app package.json
  fs.writeFileSync(packagePath, JSON.stringify(appPackage, null, 2));

  // Overwrite app jsconfig.json
  fs.writeFileSync(jsconfigPath, JSON.stringify(appJsconfig, null, 2));

  /**
   * Let the user know what happened
   */
  if (
    changedConfig.length === 0 &&
    changedDependencies.length === 0 &&
    changedScripts.length === 0 &&
    changedJsconfig.length === 0
  ) {
    console.log();
    console.log(chalk.magenta("Nothing for us to do here. Project upgrade complete."));
  } else {
    const addedDependencies = changedDependencies.filter((dependency) => !dependency.newVersion);
    const upgradedDependencies = changedDependencies.filter((dependency) => Boolean(dependency.newVersion));

    const addedScripts = changedScripts.filter((script) => script.new);
    const upgradedScripts = changedScripts.filter((script) => !script.new);

    const addedConfig = changedConfig.filter((config) => config.new);
    const upgradedConfig = changedConfig.filter((config) => !config.new);

    const addedJsconfig = changedJsconfig.filter((config) => config.new);
    const upgradedJsconfig = changedJsconfig.filter((config) => !config.new);

    // Log changes
    if (addedScripts.length > 0) {
      console.log();
      console.log(`${chalk.blue("Added scripts:")}`);
      addedScripts.forEach((script) => console.log(`- ${chalk.cyan(script.name)}`));
    }

    if (upgradedScripts.length > 0) {
      console.log();
      console.log(`${chalk.blue("Updated scripts:")}`);
      upgradedScripts.forEach((script) => console.log(`- ${chalk.cyan(script.name)}`));
    }

    if (addedDependencies.length > 0) {
      console.log();
      console.log(`${chalk.blue("Added dependencies:")}`);
      addedDependencies.forEach((dependency) =>
        console.log(`- ${chalk.cyan(`${dependency.name}@${dependency.version}`)}`)
      );
    }

    if (upgradedDependencies.length > 0) {
      console.log();
      console.log(`${chalk.blue("Upgraded dependencies:")}`);
      upgradedDependencies.forEach((dependency) =>
        console.log(
          `- ${chalk.yellow(dependency.name)} to ${chalk.green(dependency.newVersion)} from ${chalk.red(
            dependency.oldVersion
          )}`
        )
      );
    }

    if (addedConfig.length > 0) {
      console.log();
      console.log(`${chalk.blue("Added tooling config:")}`);
      addedConfig.forEach((config) => console.log(`- ${chalk.cyan(config.name)}`));
    }

    if (upgradedConfig.length > 0) {
      console.log();
      console.log(`${chalk.blue("Updated tooling config")}`);
      upgradedConfig.forEach((config) => console.log(`- ${chalk.cyan(config.name)}`));
    }

    if (addedJsconfig.length > 0) {
      console.log();
      console.log(`${chalk.blue("Added jsconfig property")}`);
      addedJsconfig.forEach((config) => console.log(`- ${chalk.cyan(config.name)}`));
    }

    if (upgradedJsconfig.length > 0) {
      console.log();
      console.log(`${chalk.blue("Updated jsconfig property")}`);
      upgradedJsconfig.forEach((config) => console.log(`- ${chalk.cyan(config.name)}`));
    }

    console.log();

    // Update dependencies based on what package manager is being used
    console.log("Installing packages. This might take a couple of minutes.");
    if (useYarn) {
      spawn.sync("yarn", ["--cwd", appPath, "install"], { stdio: "inherit" });
    } else {
      spawn.sync("npm", ["--prefix", appPath, "install"], { stdio: "inherit" });
    }
  }

  //Remove template
  console.log(`Removing template package using ${command}...`);
  console.log();

  const uninstallProc = spawn.sync(command, [remove, bcraTemplateName], {
    stdio: "inherit",
  });
  if (uninstallProc.status !== 0) {
    console.error(`\`${command} ${args.join(" ")}\` failed`);
    return;
  }
};

run();

/** Utils */

function sortObjectKeys(obj) {
  let ordered = {};
  Object.keys(obj)
    .sort()
    .forEach((key) => (ordered[key] = obj[key]));
  return ordered;
}

module.exports = sortObjectKeys;

function indexOrEnd(str, q) {
  return str.indexOf(q) === -1 ? str.length : str.indexOf(q);
}

function split(v) {
  var c = v.replace(/^v/, "").replace(/\+.*$/, "");
  var patchIndex = indexOrEnd(c, "-");
  var arr = c.substring(0, patchIndex).split(".");
  arr.push(c.substring(patchIndex + 1));
  return arr;
}

function tryParse(v) {
  return isNaN(Number(v)) ? v : Number(v);
}

function compareVersions(v1, v2) {
  var s1 = split(v1);
  var s2 = split(v2);

  for (var i = 0; i < Math.max(s1.length - 1, s2.length - 1); i++) {
    var n1 = parseInt(s1[i] || 0, 10);
    var n2 = parseInt(s2[i] || 0, 10);

    if (n1 > n2) return 1;
    if (n2 > n1) return -1;
  }

  var sp1 = s1[s1.length - 1];
  var sp2 = s2[s2.length - 1];

  if (sp1 && sp2) {
    var p1 = sp1.split(".").map(tryParse);
    var p2 = sp2.split(".").map(tryParse);

    for (i = 0; i < Math.max(p1.length, p2.length); i++) {
      if (p1[i] === undefined || (typeof p2[i] === "string" && typeof p1[i] === "number")) return -1;
      if (p2[i] === undefined || (typeof p1[i] === "string" && typeof p2[i] === "number")) return 1;

      if (p1[i] > p2[i]) return 1;
      if (p2[i] > p1[i]) return -1;
    }
  } else if (sp1 || sp2) {
    return sp1 ? -1 : 1;
  }

  return 0;
}
