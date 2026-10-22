import type { Config } from "@react-router/dev/config";

// Framework mode with server rendering on: every document request renders on the Node side,
// route loaders and actions run there, and the browser hydrates the result (app/root.tsx).
//
// appDirectory is `app/` rather than `src/`: `src/Root.tsx` is a pre-existing, case-colliding
// filename with framework mode's required `root.tsx` (this filesystem is case-insensitive), so
// pointing appDirectory at `src` would silently overwrite it. `app/` composes the existing `src/`
// tree instead: smallest diff, no rename, no collision risk.
export default {
  appDirectory: "app",
  ssr: true,
  // With the trailing slash: the dev server refuses to start unless the basename begins with
  // vite.config.mts's `base` ("/apps/flow/"), and a build must keep that slash on `base` or asset
  // URLs come out as "/apps/flowassets/...". The router strips the slash when it matches and
  // collapses it when it joins paths, so routes and hrefs are unchanged.
  basename: "/apps/flow/",
  // Splits each route's component/loader/action into its own chunk, so loader/action-only code
  // (Config/serverFetch.ts, the *.server.ts modules) is stripped from the browser bundle rather
  // than one route file eagerly pulling its whole module graph in.
  future: {
    v8_splitRouteModules: true,
    // Dev server: pre-bundle every route's dependencies at startup instead of discovering them
    // on first visit. Discovery rewrites the optimized dependency set and full-reloads every open
    // tab mid-request, which surfaces as a dead page or a Vite overlay on whichever route pulled
    // the new dependency in (this tree has many, Carbon and the editor's canvas among them).
    unstable_optimizeDeps: true,
  },
  // ESM (the framework default) rather than CJS. The server bundle is the runtime request
  // handler and pulls in the entire route tree, which includes several ESM-only packages
  // (react-markdown v8 and its whole remark/rehype/unified/vfile dependency chain). Node's
  // CommonJS require() cannot load a pure-ESM package at all (ERR_REQUIRE_ESM) - not even via
  // Vite's ssr.noExternal bundling, since noExternal only changes *how* a package is resolved
  // into the graph, not the module system of the emitted server entry itself. Node's ESM
  // loader, by contrast, can import both ESM and CJS packages natively, so this sidesteps the
  // entire class of error. Scoped to build/server/ via a generated package.json (see
  // vite.config.mts) rather than flipping this package's own "type" to "module", so eslint
  // config / commitlint config / scripts/ - all plain CJS - are unaffected.
  serverModuleFormat: "esm",
} satisfies Config;
