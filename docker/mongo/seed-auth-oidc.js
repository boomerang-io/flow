/*
 * Compose-stack-ONLY seed, run by the auth-oidc-seed one-shot service in docker-compose.yml:
 * points the `auth` settings document at the stack's local IDPZero so GET /internal/v1/auth/config
 * resolves mode=oidc.
 *
 * service-core's migrations deliberately seed oidc.issuer/oidc.clientId EMPTY - a fresh
 * non-compose install must stay unconfigured (mode=proxy) rather than trusting any default
 * issuer, and settings live only in Mongo (SettingsService has no env-var override path). So the
 * compose stack layers its values on afterwards, here, instead of changing the seeded defaults.
 *
 * The document exists only once service-core has migrated the database, and service-core may run
 * in the stack or on the host, so this waits for it rather than depending on the container.
 *
 * Idempotent: a plain $set of the two config values, safe to re-run on every `docker compose up`.
 */
const ISSUER = "http://idp.localhost:4380"; // see the idpzero service's addressing comment
const CLIENT_ID = "flow"; // matches docker/idpzero/server.yaml
const WAIT_MS = 10 * 60 * 1000;

// Collection name = FLOW_MONGO_COLLECTION_PREFIX ("flow") + "_settings" (CollectionNames rule).
const settings = db.getCollection("flow_settings");

const deadline = Date.now() + WAIT_MS;
let auth = settings.findOne({ key: "auth" });
while (!auth && Date.now() < deadline) {
  sleep(2000);
  auth = settings.findOne({ key: "auth" });
}
if (!auth) {
  throw new Error("settings(auth) not found after 10 minutes - is service-core running against this database?");
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
