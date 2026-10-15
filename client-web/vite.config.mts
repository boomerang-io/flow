/// <reference types="vitest" />
/// <reference types="vite/client" />

import { defineConfig, type Plugin } from "vite";
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

// Which dependencies the server-side code gets from Vite rather than from Node's own loader.
//
// Build (`react-router build`): every one of them. The server bundle pulls the whole route tree
// in, and that tree runs through a long tail of packages this app didn't choose with Node ESM in
// mind: @carbon/icons-react, react-lazylog and lodash are CommonJS whose exports are assembled
// dynamically, so Node's cjs-module-lexer finds no named exports to import; react-lazylog's
// "fetch-readablestream" -> "@mattiasbuelens/web-streams-polyfill" chain ends in a bare directory
// import that predates the "exports" map convention. Bundling the whole graph sidesteps all of
// it at once, and it is also what makes build/server self-contained: the runtime image carries
// `dependencies` alone (the serving packages - see package.json and the Dockerfile), not the
// hundreds of megabytes of build inputs under `devDependencies`.
//
// Dev (`react-router dev`): none of them. Vite's dev server loads every dependency through Node,
// and running the graph through the SSR transform instead is what used to break the dev server
// (`module is not defined`, `require is not defined` inside CommonJS files). What Node alone gets
// wrong is CommonJS interop: a default import of a package compiled with `exports.default`
// (react-mde, immer as use-immer imports it) arrives as the whole module object, and a named
// import from a package whose exports Node cannot detect (lodash) is refused outright.
// `legacy.proxySsrExternalModules` below restores Vite's own interop proxy for those, which
// unwraps `__esModule` defaults and resolves named imports off the module object. lodash is
// imported by subpath (`lodash/sortBy`) throughout regardless, which needs no interop at all.
// The proxy covers imports the app's own modules make; an ESM package that Node loads whole and
// that itself default-imports a CommonJS package gets no proxy (use-immer's `import produce from
// "immer"`: "TypeError: n is not a function" during the editor's server render), so such a
// package goes on DEV_SSR_BUNDLED and Vite transforms it, proxying its imports too.
//
// Vitest shares this config (Vite's SSR resolution powers its transform pipeline, even for
// jsdom-environment specs) and keeps the narrow @carbon/react entry it always had: bundling the
// whole graph there inlines vitest's own dependencies into the graph it instruments and breaks
// `toMatchSnapshot()` across ~26 spec files.
const DEV_SSR_BUNDLED = ["use-immer"];

function ssrNoExternal(command: "build" | "serve"): true | string[] {
  if (process.env.VITEST) return ["@carbon/react"];
  return command === "build" ? true : DEV_SSR_BUNDLED;
}

export default defineConfig(({ command, mode }) => {
  return {
    base: "/apps/flow/",
    build: {
      outDir: "build",
    },
    ssr: {
      noExternal: ssrNoExternal(command),
    },
    legacy: {
      proxySsrExternalModules: true,
    },
    // Browser-side dependencies the dev server would otherwise discover on first use and
    // re-optimise, reloading every open tab: the ones behind React.lazy and the ones only a
    // dependency imports, which the route crawl (react-router.config.ts, unstable_optimizeDeps)
    // does not reach. Add a package when the dev server logs "new dependencies optimized".
    optimizeDeps: {
      include: ["react-lazylog", "immer"],
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
      // active for the dev server and the build, where the app/ framework-mode tree is the
      // real entry point. Gated on process.env.VITEST (set by the vitest runner itself), not
      // `mode === "test"` - vitest itself defaults to Vite mode "test" (see the `devSourcemap`
      // check above, which relies on exactly that default), so keying off `mode` here would
      // also catch the vitest run and wrongly swap in reactRouter().
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
    // The dev server (`pnpm dev`): the same port and base path as the built server, so the
    // local IDPZero's registered redirect URI (docker/idpzero/server.yaml) matches both.
    server: {
      port: 3000,
    },
    test: {
      css: false,
      globals: true,
      environment: "jsdom",
      setupFiles: "./src/setupTests.tsx",
      // Headroom over the 5000ms RTL `asyncUtilTimeout` set in setupTests.tsx: several specs
      // chain three or four loader-backed waits in one test (WorkspaceDetailed's tab walk does
      // six), so vitest's own 5000ms default would start timing out tests that the widened RTL
      // window is meant to let finish. Raising it changes only how long a genuinely hung test
      // takes to report, never whether it fails.
      testTimeout: 20000,
      hookTimeout: 20000,
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
