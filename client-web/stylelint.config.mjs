// Stylesheet lint (`pnpm run lint:style`, gated in ci-web.yml). One rule: a raw hex colour lives in
// src/Styles/_variables.scss (the Flow accent tokens) and nowhere else; every other stylesheet
// uses a Carbon token, a Carbon palette variable or a Flow token (specifications/design-system.md).
export default {
  customSyntax: "postcss-scss",
  rules: {
    "color-no-hex": true,
  },
  overrides: [
    {
      files: ["src/Styles/_variables.scss"],
      rules: { "color-no-hex": null },
    },
  ],
};
