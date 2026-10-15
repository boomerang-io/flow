/*
 * Kube-stack-ONLY seed (run by the task-deletion-seed one-shot service in docker-compose.kube.yml,
 * once service-core is healthy): sets the task setting `deletion.policy` to Always, so a finished
 * task's Job is removed at task end.
 *
 * The seeded default, Never, keeps a finished Job (and its pod) until the retention period, and
 * Kubernetes will not release a run's storage claim while any pod that mounted it still exists -
 * finished or not (the claim protection controller counts every scheduled pod referencing the
 * claim). The dispatcher scenarios assert that a run's claim is released within one reconcile tick
 * of the run ending (e2e/tests/dispatcher-kube.spec.ts), which only holds once the workers go at
 * task end. A real deployment chooses its own policy in Admin > Settings > Task.
 *
 * Idempotent: a plain $set of one config value, safe to re-run on every `docker compose up`.
 */
const POLICY = "Always";

// Collection name = FLOW_MONGO_COLLECTION_PREFIX ("flow") + "_settings" (CollectionNames rule).
const settings = db.getCollection("flow_settings");

const task = settings.findOne({ key: "task" });
if (!task) {
  throw new Error("settings(task) not found - did service-core migrate this database?");
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
