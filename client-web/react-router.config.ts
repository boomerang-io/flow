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
  ssr: false,
  basename: "/apps/flow",
  // Splits each route's component/loader/action into its own chunk instead of one route file
  // eagerly pulling its whole module graph into the server-build entry - SPA mode only ever
  // renders the root route (see the ssr:false comment above), so the other 22 routes' code
  // (WorkflowEditor, WorkflowRun, etc. - none of it SSR-safe, all of it written assuming a
  // browser) has no business being reachable from that build-time-only server bundle at all.
  future: {
    v8_splitRouteModules: true,
  },
  // The build still produces an internal, build-time-only server bundle (used solely to
  // prerender the root route into server/build/index.html - see the ssr:false comment above);
  // it needs to load in plain Node without ESM support declared in this project's
  // package.json ("type": "module" isn't set, and setting it would flip every other .js file
  // in this project - eslint config, commitlint config, scripts/ - to ESM too), so it's emitted
  // as CommonJS rather than the framework's ESM default.
  serverModuleFormat: "cjs",
} satisfies Config;
