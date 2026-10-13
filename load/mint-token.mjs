#!/usr/bin/env node
// Mints a `bfg_` global token against the compose stack without a browser. Node 20+, no
// dependencies. Drives the same sign-in the webapp performs: IDPZero authorization-code flow with
// PKCE as `usr-flow-admin`, POST /api/v2/auth/exchange for a session cookie, then POST /api/v2/token.
//
//   FLOW_TOKEN=$(node load/mint-token.mjs)
//
//   FLOW_URL      service-core origin (default http://localhost:7700)
//   IDP_USER      IDPZero subject to sign in as (default usr-flow-admin)
//   IDP_ADDRESS   host:port to connect to for the issuer, when the issuer's own address is not
//                 reachable from this machine (e.g. another process owns host port 4380):
//                 IDP_ADDRESS=$(docker inspect flow-idpzero-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'):4380
//
// The IDP legs use node:http rather than fetch because the issuer is derived from the Host header
// (docker/idpzero/server.yaml) and fetch does not let a caller set Host.

import { createHash, randomBytes } from "node:crypto";
import http from "node:http";

const FLOW_URL = (process.env.FLOW_URL ?? "http://localhost:7700").replace(/\/$/, "");
const IDP_USER = process.env.IDP_USER ?? "usr-flow-admin";
const REDIRECT_URI = process.env.REDIRECT_URI ?? "http://localhost:3000/apps/flow/auth/callback";
const b64url = (buf) => buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

const config = await (await fetch(`${FLOW_URL}/api/v2/auth/config`)).json();
if (config.mode !== "oidc") throw new Error(`auth mode is ${config.mode}, not oidc; is the compose stack up?`);
const issuer = new URL(config.issuer);
const [connectHost, connectPort] = (process.env.IDP_ADDRESS ?? issuer.host).split(":");

function idp(method, path, { body, cookie } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: connectHost, port: Number(connectPort ?? 80), method, path,
        headers: { Host: issuer.host, ...(cookie ? { Cookie: cookie } : {}),
          ...(body ? { "Content-Type": "application/x-www-form-urlencoded", "Content-Length": Buffer.byteLength(body) } : {}) } },
      (res) => { let text = ""; res.on("data", (c) => (text += c)); res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, text })); },
    );
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

const verifier = b64url(randomBytes(32));
const state = b64url(randomBytes(16));
const nonce = b64url(randomBytes(16));
const authorize = new URLSearchParams({
  client_id: config.clientId, redirect_uri: REDIRECT_URI, response_type: "code", scope: "openid profile email",
  state, nonce, code_challenge: b64url(createHash("sha256").update(verifier).digest()), code_challenge_method: "S256",
});

let res = await idp("GET", `/authorize?${authorize}`);
const cookies = [];
let code = null;
for (let hop = 0; hop < 6 && !code; hop++) {
  if (res.headers["set-cookie"]) cookies.push(...res.headers["set-cookie"].map((c) => c.split(";")[0]));
  const location = res.headers.location;
  if (res.status === 200 && res.text.includes('name="req"')) {
    // IDPZero's passwordless picker: <form action="/login" method="post"> with a hidden req id.
    const reqId = res.text.match(/name="req" value="([^"]+)"/)[1];
    res = await idp("POST", "/login", { body: new URLSearchParams({ req: reqId, username: IDP_USER }).toString(), cookie: cookies.join("; ") });
  } else if (location?.startsWith(REDIRECT_URI)) {
    const back = new URL(location);
    if (back.searchParams.get("state") !== state) throw new Error("state mismatch on the callback");
    code = back.searchParams.get("code");
  } else if (location) {
    res = await idp("GET", location.startsWith("http") ? new URL(location).pathname + new URL(location).search : location, { cookie: cookies.join("; ") });
  } else {
    throw new Error(`unexpected IDP response ${res.status}: ${res.text.slice(0, 200)}`);
  }
}
if (!code) throw new Error("no authorization code after 6 hops");

const token = await idp("POST", "/oauth/token", {
  body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI, client_id: config.clientId, code_verifier: verifier }).toString(),
});
if (token.status !== 200) throw new Error(`token endpoint ${token.status}: ${token.text.slice(0, 200)}`);
const idToken = JSON.parse(token.text).id_token;

const exchange = await fetch(`${FLOW_URL}/api/v2/auth/exchange`, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ idToken, nonce }),
});
if (!exchange.ok) throw new Error(`exchange ${exchange.status}: ${await exchange.text()}`);
const session = exchange.headers.get("set-cookie")?.match(/flow_session=([^;]+)/)?.[1];
if (!session) throw new Error("exchange returned no flow_session cookie");

const mint = await fetch(`${FLOW_URL}/api/v2/token`, {
  method: "POST", headers: { "Content-Type": "application/json", Cookie: `flow_session=${session}` },
  body: JSON.stringify({ type: "global", name: `load-${Date.now()}`, description: "load harness", permissions: ["**/**"],
    expirationDate: new Date(Date.now() + 24 * 3600 * 1000).toISOString() }),
});
if (!mint.ok) throw new Error(`POST /api/v2/token ${mint.status}: ${await mint.text()}`);
console.log((await mint.json()).token);
