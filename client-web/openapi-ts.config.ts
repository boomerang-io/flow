import { defineConfig } from "@hey-api/openapi-ts";

// `pnpm gen:api`: one entry per contract, each generated into src/Api/<contract> (the run empties
// that directory first, so contracts never share one). src/Api is checked in whole; ci-web.yml
// regenerates it and fails when the checked-in copy differs.
export default defineConfig([
  {
    // The public API, the contract service-core is tested against.
    input: "../contracts/api-v2.yaml",
    output: "src/Api/api-v2",
    plugins: ["@hey-api/typescript"],
  },
]);
