/// <reference types="vitest" />
/// <reference types="vite/client" />

import { defineConfig, loadEnv } from "vite";
import { resolve } from "path";
import autoprefixer from "autoprefixer";
import react from "@vitejs/plugin-react";
import { reactRouter } from "@react-router/dev/vite";
import eslint from "vite-plugin-eslint";
import svgrPlugin from "vite-plugin-svgr";
const projectRootDir = resolve(__dirname);

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    base: "/apps/flow/",
    build: {
      outDir: "build",
    },
    // ssr:false still produces an internal, build-time-only server bundle solely to prerender
    // the root route into server/build/index.html (see react-router.config.ts) - it is never
    // shipped or executed at runtime, and future.v8_splitRouteModules there keeps it scoped to
    // just what app/root.tsx itself reaches. Within that reach, @carbon/react's icon re-export
    // is ESM-only and would otherwise be left as a raw `require()` that a CommonJS Node load
    // can't parse - bundle it inline instead of externalizing it.
    ssr: {
      noExternal: ["@carbon/react"],
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