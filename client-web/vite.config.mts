/// <reference types="vitest" />
/// <reference types="vite/client" />

import { defineConfig, loadEnv, type Plugin } from "vite";
import { resolve, join } from "path";
import { writeFileSync } from "fs";
import autoprefixer from "autoprefixer";
import react from "@vitejs/plugin-react";
import { reactRouter } from "@react-router/dev/vite";
import eslint from "vite-plugin-eslint";
import svgrPlugin from "vite-plugin-svgr";
const projectRootDir = resolve(__dirname);

// react-router.config.ts sets serverModuleFormat: "esm", so build/server/index.js (and its
// chunks) contain real `import`/`export` syntax - but this package's own package.json
// deliberately does NOT set "type": "module" (see react-router.config.ts for why: eslint
// config, commitlint config, and scripts/ all stay CJS). Node resolves module format from the
// nearest package.json, so without one inside build/server, Node would load index.js as CJS
// and hit "Cannot use import statement outside a module". Scoping a minimal package.json to
// just that output directory - the standard Node technique for mixing module formats in one
// repo - declares ESM for the server bundle only, leaving every other file's format alone.
function writeServerPackageJson(): Plugin {
  let isSsrBuild = false;
  return {
    name: "write-server-package-json",
    apply: "build",
    configResolved(resolvedConfig) {
      isSsrBuild = Boolean(resolvedConfig.build.ssr);
    },
    writeBundle(options) {
      if (!isSsrBuild || !options.dir) return;
      writeFileSync(join(options.dir, "package.json"), JSON.stringify({ type: "module" }, null, 2) + "\n");
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    base: "/apps/flow/",
    build: {
      outDir: "build",
    },
    // Full SSR pulls the entire route tree into the server bundle (unlike the previous
    // ssr:false build, which only ever touched app/root.tsx), and that tree runs through a long
    // tail of dependencies this app didn't choose with Node ESM in mind: @carbon/charts'
    // dist/index.mjs statically imports "d3-cloud" (its optional WordCloud chart dependency,
    // never installed here, since this app doesn't use word-cloud charts) as if it were
    // required; @carbon/icons-react, react-lazylog, and lodash are CJS packages whose exports
    // are assembled dynamically (mixin/re-export patterns) rather than with statically
    // analyzable `exports.foo = foo` assignments, which Node's cjs-module-lexer can't detect
    // named exports from; react-lazylog's own "fetch-readablestream" ->
    // "@mattiasbuelens/web-streams-polyfill" chain points at a bare directory import that
    // predates Node's "exports" map convention. Each of those was confirmed individually by
    // working through the resulting runtime errors one at a time - the fix in every case was
    // "let Vite bundle this instead of leaving it as a raw Node import/require", which is what
    // `noExternal: true` does for the whole graph at once. Given how many unrelated packages
    // hit this (a handful found here is unlikely to be the last), hand-maintaining an allowlist
    // trades a one-time cost for an ongoing one - every future dependency with the same
    // CJS-interop shape would silently break the server build again. The trade-off is a
    // larger, slower-to-produce server bundle, which is acceptable for a build-time cost.
    // (`reactflow` used to be on this list - it's why the DAG canvas needed `noExternal` at all.
    // Its v12 successor `@xyflow/react` ships real ESM with a statically analyzable `exports`
    // map, so it no longer needs bundling on its own account - but the blanket `true` still
    // covers the other packages above, so it stays.)
    //
    // Scoped to the real SSR build only (`!process.env.VITEST`, the same signal the `plugins`
    // array below already keys off of): vitest's node-environment test runs go through this
    // SAME `ssr` config (Vite's SSR module resolution powers vitest's transform pipeline even
    // for jsdom-environment tests), and blanket noExternal there inlines vitest's own
    // dependencies (its snapshot stack-trace parser among them) into the graph it's trying to
    // instrument, breaking `toMatchSnapshot()` across ~26 spec files
    // (`TypeError: Cannot read properties of undefined (reading 'match')` inside
    // @vitest/snapshot). Vitest never touches the SSR entry (it renders Feature components
    // directly - see the `plugins` comment below) and hits none of the ESM-interop problems
    // above, so it keeps the original, narrow `@carbon/react` entry that predates ssr:true.
    ssr: {
      noExternal: process.env.VITEST ? ["@carbon/react"] : true,
    },
    css: {
      devSourcemap: mode !== "test",
      preprocessorOptions: {
        scss: {
          quietDeps: true,
        },
      },
      postcss: {
        plugins: [autoprefixer],
      },
    },
    plugins: [
      // vitest's own transform pipeline never touches app/root.tsx or app/routes.ts (unit
      // tests render Feature components directly, not through the router), so it stays on
      // the plain React plugin it always used - reactRouter() (which supersedes
      // @vitejs/plugin-react, bundling its own JSX/Fast-Refresh transform) only needs to be
      // active for the dev server and `vite build`, where the app/ framework-mode tree is
      // the real entry point. Gated on process.env.VITEST (set by the vitest runner itself),
      // not `mode === "test"` - `start:test` also runs with --mode test (it serves the real
      // app, framework-mode included, for the Cypress e2e suite) and must still get
      // reactRouter().
      process.env.VITEST ? react() : reactRouter(),
      eslint(),
      svgrPlugin({
        svgrOptions: {
          icon: true,
        },
      }),
      writeServerPackageJson(),
    ],
    resolve: {
      alias: [
        { find: "ApiServer", replacement: resolve(projectRootDir, "./src/ApiServer") },
        { find: "Assets", replacement: resolve(projectRootDir, "./src/Assets") },
        { find: "Components", replacement: resolve(projectRootDir, "./src/Components") },
        { find: "Config", replacement: resolve(projectRootDir, "./src/Config") },
        { find: "Constants", replacement: resolve(projectRootDir, "./src/Constants") },
        { find: "Features", replacement: resolve(projectRootDir, "./src/Features") },
        { find: "Hooks", replacement: resolve(projectRootDir, "./src/Hooks") },
        { find: "State", replacement: resolve(projectRootDir, "./src/State") },
        { find: "Styles", replacement: resolve(projectRootDir, "./src/Styles") },
        { find: "Types", replacement: resolve(projectRootDir, "./src/Types") },
        { find: "Utils", replacement: resolve(projectRootDir, "./src/Utils") },
        { find: "~ibm-design-colors", replacement: "ibm-design-colors" },
        { find: "~normalize-scss", replacement: "normalize-scss" },
      ],
    },
    server: {
      port: 3000,
      proxy: mode === "portforward" ? createPortforwardConfig(env.AUTH_JWT) : undefined,
    },
    test: {
      css: false,
      globals: true,
      environment: "jsdom",
      setupFiles: "./src/setupTests.tsx",
      coverage: {
        reporter: ["json"],
        include: [
          "**/src/Components/**/*.{js,jsx}",
          "**/src/Features/**/*.{js,jsx}",
          "**/src/Hooks/**/*.{js,jsx}",
          "**/src/State/**/*.{js,jsx}",
          "**/src/Utils/**/*.{js,jsx}",
        ],
      },
    },
  };
});

const portForwardMap = {
  "/api": 8081,
};

// Map service context paths to the local port that you have forwarded
function createPortforwardConfig(jwt?: string) {
  return Object.entries(portForwardMap).reduce((proxyMap, [path, port]) => {
    proxyMap[path] = {
      changeOrigin: true,
      headers: { Authorization: `Bearer ${jwt}` },
      rewrite: (path) => path.replace(/^\/api/, "/api/v2"),
      target: `http://localhost:${port}`,
    };
    return proxyMap;
  }, {});
}