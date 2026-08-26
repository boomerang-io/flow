/**
 * Boomerang Flow client-web server.
 *
 * SSR is on (react-router.config.ts: ssr: true), so the frontend build no longer produces a
 * static server/build/index.html - the real artifact is build/server/index.js, a ServerBuild
 * module (assets/routes/entry/...) that has to be wrapped with react-router's
 * createRequestHandler() and invoked per request, plus build/client/, the browser assets it
 * references. A static-file server (the previous @boomerang-io/webapp-spa-server) has nothing
 * to serve against that shape.
 *
 * This is a small custom Express server rather than `@react-router/serve` (the framework's own
 * CLI server) for two concrete reasons this deployment needs and the CLI doesn't give a hook
 * for:
 *   1. A `/health` endpoint that answers WITHOUT running the SSR handler - a k8s/OpenShift
 *      liveness/readiness probe should not execute route loaders (which call out to
 *      CORE_SERVICE_INTERNAL_ORIGIN, see Config/serverFetch.ts) on every poll.
 *   2. window._SERVER_DATA injection (below) - the runtime env values the browser bundle reads
 *      (Config/appConfig.ts, Config/servicesConfig.ts, Types/index.tsx) used to be templated
 *      into index.html by the old server at container boot; there is no index.html to template
 *      anymore, so this server does the equivalent as a response transform around the SSR
 *      handler's HTML output. `@react-router/serve` has no extension point for this.
 * Neither of these hardcodes any networking assumption (host, scheme, upstream address) - see
 * the trust-proxy and process.env notes below - honouring CLAUDE.md's deployment constraint
 * that enterprises run this behind reverse proxies and internal CAs.
 */
import express from "express";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequestHandler } from "react-router";
import { createReadableStreamFromReadable, writeReadableStreamToWritable } from "@react-router/node";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// `build/` sits alongside `server/` (both produced/consumed relative to the client-web package
// root - see package.json's `build:vite` and the `prod:server`/Dockerfile call sites) so this
// resolves identically whether the process is started from a checkout (`cd server && node
// index.js`) or from the container image, as long as that sibling layout is preserved - the
// Dockerfile deliberately copies `server/` and `build/` as siblings for exactly this reason.
const BUILD_DIR = path.resolve(__dirname, "..", "build");
const CLIENT_DIR = path.join(BUILD_DIR, "client");
const SERVER_BUILD_ENTRY = path.join(BUILD_DIR, "server", "index.js");

const mode = process.env.NODE_ENV ?? "production";

// The server bundle is emitted as real ESM (react-router.config.ts: serverModuleFormat: "esm" -
// see its comment for why) with its own scoped `{"type":"module"}` package.json, so it has to be
// `import()`ed rather than `require()`d; a `file://` URL keeps this portable across platforms.
const build = await import(pathToFileURL(SERVER_BUILD_ENTRY).href);
const handleRequest = createRequestHandler(build, mode);

const app = express();
app.disable("x-powered-by");

// Enterprises run this behind reverse proxies / load balancers doing TLS termination (CLAUDE.md
// deployment constraint) - trust the X-Forwarded-* chain instead of assuming this process is the
// public edge, so req.protocol/req.ip (and anything loaders derive from them) are correct.
app.set("trust proxy", true);

// Liveness/readiness - deliberately short-circuits before the SSR handler (see file header).
app.get("/health", (_req, res) => {
  res.status(200).json({ status: "UP" });
});

// The build emits basename-prefixed asset URLs (/apps/flow/assets/... - react-router.config.ts's
// basename + vite.config.mts's base: "/apps/flow/"), but Vite also copies `public/`'s contents
// (favicon.ico, manifest.json, newrelic*.js) straight into build/client's root, and app/root.tsx
// links to those at "/" with no basename prefix. Serve the one client build directory at both
// locations rather than picking a side - whichever shape a given asset is actually requested at
// resolves, and express.static no-ops (calls next()) on a miss either way.
const staticAssetOptions = { immutable: true, maxAge: "1y", index: false };
app.use("/apps/flow/assets", express.static(path.join(CLIENT_DIR, "assets"), staticAssetOptions));
app.use("/assets", express.static(path.join(CLIENT_DIR, "assets"), staticAssetOptions));
app.use("/apps/flow", express.static(CLIENT_DIR, { index: false }));
app.use(express.static(CLIENT_DIR, { index: false }));

// The subset of window._SERVER_DATA keys the frontend actually declares (Types/index.tsx) -
// sourced straight from process.env so every value can be set per deployment (this compose
// stack, a k8s Deployment, ...) without a rebuild. Only keys explicitly present in the
// environment are forwarded, so unset values fall through to the frontend's own hardcoded
// defaults (Config/appConfig.ts / Config/servicesConfig.ts) instead of being clobbered with
// empty strings.
const SERVER_DATA_ENV_KEYS = [
  "APP_ROOT",
  "CORE_ENV_URL",
  "CORE_SERVICE_ENV_URL",
  "EMBEDDED_MODE",
  "PRODUCT_ENV_URL",
  "PRODUCT_SERVICE_ENV_URL",
  "PRODUCT_STANDALONE",
];

function buildServerData() {
  const data = {};
  for (const key of SERVER_DATA_ENV_KEYS) {
    if (process.env[key] !== undefined) {
      data[key] = process.env[key];
    }
  }
  return data;
}

function injectServerData(html) {
  const script = `<script>window._SERVER_DATA = ${JSON.stringify(buildServerData())};</script>`;
  return html.includes("</head>") ? html.replace("</head>", `${script}</head>`) : html + script;
}

// Node <-> Fetch bridges. Handled by hand (rather than @react-router/node's own
// createRequestListener, which wraps this same conversion) so the HTML branch below has a hook
// to inject window._SERVER_DATA before the response is sent - createRequestListener hands the
// Response straight to the client with no interception point.
function createFetchRequest(req, res) {
  const origin = `${req.protocol}://${req.get("host") ?? "localhost"}`;
  const url = new URL(req.originalUrl ?? req.url, origin);

  const controller = new AbortController();
  res.on("close", () => controller.abort());

  const headers = new Headers();
  for (const [key, value] of Object.entries(req.headers)) {
    if (value == null) continue;
    for (const v of Array.isArray(value) ? value : [value]) {
      headers.append(key, v);
    }
  }

  const init = { method: req.method, headers, signal: controller.signal };
  if (req.method !== "GET" && req.method !== "HEAD") {
    init.body = createReadableStreamFromReadable(req);
    init.duplex = "half";
  }
  return new Request(url.href, init);
}

async function sendFetchResponse(nodeRes, response) {
  nodeRes.statusCode = response.status;
  nodeRes.statusMessage = response.statusText;

  for (const [key, value] of response.headers.entries()) {
    if (key.toLowerCase() === "set-cookie") continue;
    nodeRes.setHeader(key, value);
  }
  // Headers.entries() folds repeated Set-Cookie into one value; getSetCookie() (Node >=19)
  // preserves each cookie as its own header.
  if (typeof response.headers.getSetCookie === "function") {
    const cookies = response.headers.getSetCookie();
    if (cookies.length > 0) nodeRes.setHeader("set-cookie", cookies);
  }

  const isHtmlDocument = (response.headers.get("content-type") ?? "").includes("text/html");
  if (isHtmlDocument) {
    // Buffered rather than streamed so window._SERVER_DATA can be injected into the completed
    // document. This app's SSR isn't using Suspense/deferred streaming today (app/root.tsx
    // renders a single synchronous document), so the cost is one full-page string, not a
    // partial-hydration stream cut short.
    const html = await response.text();
    const injected = injectServerData(html);
    nodeRes.removeHeader("content-length");
    nodeRes.end(injected);
    return;
  }

  if (!response.body) {
    nodeRes.end();
    return;
  }
  await writeReadableStreamToWritable(response.body, nodeRes);
}

// No path pattern - matches every method/path that fell through the static middleware above.
// (Deliberately not `app.all("*", ...)`: Express 5's path-to-regexp v8 dropped the bare "*"
// wildcard in favour of named params like "/*splat", and a plain `use()` catch-all needs none of
// that syntax while working identically on Express 4 and 5.)
app.use(async (req, res, next) => {
  try {
    const request = createFetchRequest(req, res);
    const response = await handleRequest(request);
    await sendFetchResponse(res, response);
  } catch (error) {
    next(error);
  }
});

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error(err);
  if (!res.headersSent) {
    res.status(500).send("Internal Server Error");
  }
});

const port = Number(process.env.PORT) || 3000;
// No host argument - bind every interface so this is reachable inside a container regardless of
// what network name/address fronts it (see the trust-proxy note above for the same reasoning).
app.listen(port, () => {
  console.log(`client-web server listening on port ${port} (mode=${mode})`);
});
