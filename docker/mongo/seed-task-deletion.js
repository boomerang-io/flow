/*
 * Compose-stack-ONLY seed, run by the task-deletion-seed one-shot service in docker-compose.yml:
 * sets the task setting `deletion.policy` to Always, so a finished task's Job is removed at task
 * end.
 *
 * The seeded default, Never, keeps a finished Job (and its pod) until the retention period, and
 * Kubernetes will not release a run's storage claim while any pod that mounted it still exists -
 * finished or not (the claim protection controller counts every scheduled pod referencing the
 * claim). The dispatcher scenarios assert that a run's claim is released within one reconcile tick
 * of the run ending (e2e/tests/dispatcher-kube.spec.ts), which only holds once the workers go at
 * task end. A real deployment chooses its own policy in Admin > Settings > Task.
 *
 * The document exists only once service-core has migrated the database, and service-core may run
 * in the stack or on the host, so this waits for it rather than depending on the container.
 *
 * Idempotent: a plain $set of one config value, safe to re-run on every `docker compose up`.
 */
const POLICY = "Always";
const WAIT_MS = 10 * 60 * 1000;

// Collection name = FLOW_MONGO_COLLECTION_PREFIX ("flow") + "_settings" (CollectionNames rule).
const settings = db.getCollection("flow_settings");

const deadline = Date.now() + WAIT_MS;
let task = settings.findOne({ key: "task" });
while (!task && Date.now() < deadline) {
  sleep(2000);
  task = settings.findOne({ key: "task" });
}
if (!task) {
  throw new Error("settings(task) not found after 10 minutes - is service-core running against this database?");
}

const result = settings.updateOne(
  { key: "task" },
  { $set: { "config.$[policy].value": POLICY } },
  { arrayFilters: [{ "policy.key": "deletion.policy" }] },
);

print(
  `seed-task-deletion: deletion.policy=${POLICY} ` +
    `matched=${result.matchedCount} modified=${result.modifiedCount} (0 modified = already seeded)`,
);
