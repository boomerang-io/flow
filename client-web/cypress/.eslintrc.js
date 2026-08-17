module.exports = {
  root: true,
  plugins: ["cypress"],
  extends: ["plugin:cypress/recommended"],
  env: {
    "cypress/globals": true,
  },
  overrides: [
    {
      files: ["**/*.cy.*"],
      rules: {
        "cypress/no-unnecessary-waiting": 0,
      },
    },
  ],
};
