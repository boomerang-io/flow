/*
 * Compose-stack-ONLY seed (run by the auth-oidc-seed one-shot service in docker-compose.yml,
 * after service-loader completes): points the `auth` settings document at the stack's local
 * IDPZero so GET /api/v2/auth/config resolves mode=oidc.
 *
 * service-loader's _0035__AddAuthSettings deliberately seeds oidc.issuer/oidc.clientId EMPTY -
 * a fresh non-compose install must stay unconfigured (mode=proxy) rather than trusting any
 * default issuer, and settings live only in Mongo (SettingsService has no env-var override
 * path). So the compose stack layers its values on afterwards, here, instead of changing the
 * loader's defaults.
 *
 * Idempotent: a plain $set of the two config values, safe to re-run on every `docker compose up`.
 */
const ISSUER = "http://idp.localhost:4380"; // see the idpzero service's addressing comment
const CLIENT_ID = "flow"; // matches docker/idpzero/server.yaml

// Collection name = FLOW_MONGO_COLLECTION_PREFIX ("flow") + "_settings" (CollectionNames rule).
const settings = db.getCollection("flow_settings");

const auth = settings.findOne({ key: "auth" });
if (!auth) {
  // Ordering bug guard: compose runs this only after service-loader completed successfully,
  // so the document must exist. Fail loudly rather than silently seeding nothing.
  throw new Error("settings(auth) not found - did service-loader run against this database?");
}

const result = settings.updateOne(
  { key: "auth" },
  {
    $set: {
      "config.$[issuer].value": ISSUER,
      "config.$[client].value": CLIENT_ID,
    },
  },
  { arrayFilters: [{ "issuer.key": "oidc.issuer" }, { "client.key": "oidc.clientId" }] },
);

print(
  `seed-auth-oidc: issuer=${ISSUER} clientId=${CLIENT_ID} ` +
    `matched=${result.matchedCount} modified=${result.modifiedCount} (0 modified = already seeded)`,
);
