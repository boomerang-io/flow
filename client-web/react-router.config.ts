import type { Config } from "@react-router/dev/config";

// Framework mode, SSR intentionally OFF ("SPA Mode" per the React Router docs: the router/build
// tooling still resolves the route tree and code-splits per route, but only the root route is
// ever rendered on the Node side (at build time, to produce the static server/build/index.html
// shell) - every other route only ever renders in the browser after hydration, exactly as it did
// under the previous plain-Vite + createBrowserRouter setup. Turning ssr on is the next step,
// deliberately not part of this change.
//
// appDirectory stays at the new `app/` directory rather than `src/` - `src/Root.tsx` is a
// pre-existing, case-colliding filename with framework mode's required `root.tsx` (this
// filesystem is case-insensitive), so pointing appDirectory at `src` would silently overwrite it.
// `app/` composes the existing `src/` tree instead: smallest diff, no rename, no collision risk.
export default {
  appDirectory: "app",
  ssr: true,
  basename: "/apps/flow",
  // Splits each route's component/loader/action into its own chunk instead of one route file
  // eagerly pulling its whole module graph into the server-build entry - SPA mode only ever
  // renders the root route (see the ssr:false comment above), so the other 22 routes' code
  // (WorkflowEditor, WorkflowRun, etc. - none of it SSR-safe, all of it written assuming a
  // browser) has no business being reachable from that build-time-only server bundle at all.
  future: {
    v8_splitRouteModules: true,
  },
  // ESM (the framework default) rather than CJS. CJS was the right choice for the previous
  // ssr:false build, where server/build's only job was a one-shot, build-time-only Node
  // require() of app/root.tsx to prerender server/build/index.html - a narrow surface with no
  // real dependency graph. Under ssr:true, this server bundle is the actual runtime request
  // handler and pulls in the entire route tree, which includes several ESM-only packages
  // (react-markdown v8 and its whole remark/rehype/unified/vfile dependency chain). Node's
  // CommonJS require() cannot load a pure-ESM package at all (ERR_REQUIRE_ESM) - not even via
  // Vite's ssr.noExternal bundling, since noExternal only changes *how* a package is resolved
  // into the graph, not the module system of the emitted server entry itself. Node's ESM
  // loader, by contrast, can import both ESM and CJS packages natively, so this sidesteps the
  // entire class of error instead of hand-maintaining a noExternal allowlist for every
  // ESM-only transitive dependency. Scoped to build/server/ via a generated package.json
  // (see vite.config.mts) rather than flipping this package's own "type" to "module", so
  // eslint config / commitlint config / scripts/ - all plain CJS - are unaffected.
  serverModuleFormat: "esm",
} satisfies Config;
