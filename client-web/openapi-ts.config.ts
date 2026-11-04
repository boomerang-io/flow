import { defineConfig } from "@hey-api/openapi-ts";

// `pnpm gen:api`: the public API's types, generated from the contract service-core is tested against.
// src/Api is generated and checked in whole (the run empties it first); ci-web.yml regenerates it and
// fails when the checked-in copy differs.
export default defineConfig({
  input: "../contracts/api-v2.yaml",
  output: "src/Api",
  plugins: ["@hey-api/typescript"],
});
